package com.aicostops.gateway.advisor;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService.TransportFailure;
import com.aicostops.gateway.metering.GatewayUsageObservation;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatAdapterRegistry;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderContextResolutionException;
import com.aicostops.gateway.provider.ProviderExecutionContextResolver;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.request.GatewayRequestLifecycleService;
import com.aicostops.gateway.request.GatewayRequestOrchestrator;
import com.aicostops.gateway.request.GatewayRequestService;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Governed advisor execution worker (M18 V3).
 *
 * <p>Claims durable advisor jobs and executes them through the normal
 * Gateway governance chain: routing, budget admission, dispatch fence,
 * usage, settlement and Ledger. The claim commits before any Provider I/O;
 * a linked Gateway execution is converged, never blindly redispatched.
 */
@Service
public class AdvisorInferenceWorker {

    private static final Logger LOG = LoggerFactory.getLogger(AdvisorInferenceWorker.class);
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final int ADVISOR_MAX_TOKENS = 1024;
    private static final Set<String> TERMINAL_PRE_DISPATCH =
            Set.of("REJECTED_BUDGET", "CANCELED_PRE_DISPATCH", "FAILED_PRE_DISPATCH");
    private static final Set<String> TERMINAL_POST_DISPATCH = Set.of(
            "CANCELED_AFTER_DISPATCH", "TIMED_OUT_AFTER_DISPATCH", "FAILED_AFTER_DISPATCH");

    private final AdvisorJobMapper jobs;
    private final GatewayRequestOrchestrator orchestrator;
    private final GatewayRequestLifecycleService lifecycleService;
    private final ProviderChatAdapterRegistry adapterRegistry;
    private final ProviderExecutionContextResolver contextResolver;
    private final GatewayUsageFinalizationService usageFinalization;
    private final ObjectMapper objectMapper;
    private final AdvisorSnapshotPromptBuilder prompts;
    private final AdvisorNarrativeValidator narratives;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public AdvisorInferenceWorker(
            AdvisorJobMapper jobs,
            GatewayRequestOrchestrator orchestrator,
            GatewayRequestLifecycleService lifecycleService,
            ProviderChatAdapterRegistry adapterRegistry,
            ProviderExecutionContextResolver contextResolver,
            GatewayUsageFinalizationService usageFinalization,
            ObjectMapper objectMapper,
            AdvisorSnapshotPromptBuilder prompts,
            AdvisorNarrativeValidator narratives,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.orchestrator = orchestrator;
        this.lifecycleService = lifecycleService;
        this.adapterRegistry = adapterRegistry;
        this.contextResolver = contextResolver;
        this.usageFinalization = usageFinalization;
        this.objectMapper = objectMapper;
        this.prompts = prompts;
        this.narratives = narratives;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${aicostops.gateway.advisor-worker-delay-ms:30000}")
    public void tick() {
        try {
            jobs.reclaimOrphans(clock.instant());
            convergeStuckLinked();
            for (var claimed = claimOne(); claimed != null; claimed = claimOne()) {
                execute(claimed);
            }
        } catch (RuntimeException ex) {
            LOG.warn("Advisor worker tick failed", ex);
        }
    }

    public ClaimedJob claimOne() {
        // Lock + fencing-token assignment commit atomically; the select and
        // the update must share one transaction (same-class @Transactional
        // would be bypassed here, so the template is used explicitly).
        return transactions.execute(status -> {
            var now = clock.instant();
            var candidate = jobs.claimEligibleAny(now);
            if (candidate == null) return null;
            var token = HexFormat.of().formatHex(randomBytes(20));
            if (jobs.markClaimed(candidate.id(), token, now.plus(LEASE), now) != 1) return null;
            return new ClaimedJob(candidate.id(), candidate.orgId(), token, candidate.attemptCount(),
                    candidate.subjectType(), candidate.subjectId(), candidate.evidenceRefsJson());
        });
    }

    /**
     * P1-1 explicit transactional terminal transition for (job, current attempt).
     * Both updates must affect exactly one row or the whole short transaction rolls back.
     * Provider I/O never occurs inside. Success maps to COMPLETED/COMPLETED, failure maps
     * to FAILED/FAILED with the exact safe failure code on the attempt.
     */
    void terminalizeFailed(long jobId, String token, int attemptNo, String failureCode) {
        var now = clock.instant();
        terminalizeFailedAt(jobId, token, attemptNo, failureCode, now);
    }

    void terminalizeFailedAt(long jobId, String token, int attemptNo, String failureCode, Instant now) {
        var code = failureCode == null || failureCode.isBlank() ? "PROVIDER_UNAVAILABLE" : failureCode;
        var ok = transactions.execute(status -> {
            if (jobs.markFailed(jobId, token, code, now) != 1) {
                status.setRollbackOnly();
                return false;
            }
            if (jobs.failAttempt(jobId, attemptNo, code) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (ok == null || !ok) {
            throw new IllegalStateException("Advisor terminal transition failed; job update rolled back.");
        }
    }

    void terminalizeCompleted(long jobId, String token, int attemptNo) {
        var now = clock.instant();
        var ok = transactions.execute(status -> {
            if (jobs.markCompleted(jobId, token, now) != 1) {
                status.setRollbackOnly();
                return false;
            }
            if (jobs.completeAttempt(jobId, attemptNo) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (ok == null || !ok) {
            throw new IllegalStateException("Advisor terminal transition failed; job update rolled back.");
        }
    }

    void terminalizeCompletedWithExplanation(long orgId, long jobId, String token, int attemptNo,
            String summary, String drivers, String actionsJson, String warningsJson, String refsJson) {
        var now = clock.instant();
        var ok = transactions.execute(status -> {
            try {
                jobs.insertExplanation(orgId, jobId, attemptNo, summary, drivers, actionsJson,
                        warningsJson, refsJson, now);
            } catch (RuntimeException ex) {
                status.setRollbackOnly();
                return false;
            }
            if (jobs.markCompleted(jobId, token, now) != 1) {
                status.setRollbackOnly();
                return false;
            }
            if (jobs.completeAttempt(jobId, attemptNo) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (ok == null || !ok) {
            throw new IllegalStateException("Advisor terminal transition failed; job update rolled back.");
        }
    }

    private void execute(ClaimedJob claimed) {
        try {
            runGoverned(claimed);
        } catch (RuntimeException ex) {
            LOG.warn("Advisor job {} failed", claimed.jobId(), ex);
            var code = mapFailureCode(ex);
            // P1-1 atomic terminal convergence: job+attempt must commit together; never split,
            // never swallow affected-row mismatches. Provider I/O never occurs here.
            try {
                terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), code);
            } catch (RuntimeException nested) {
                LOG.warn("Advisor job {} failure could not be recorded", claimed.jobId(), nested);
            }
        }
    }

    private void runGoverned(ClaimedJob claimed) {
        // P0 frozen-job principle: execute the exact profile revision this job was requested under
        // (persisted advisor_profile_id), never the current ACTIVE revision. Legacy jobs without a
        // binding resolve through their stored version. A job whose bound revision is no longer
        // ACTIVE fails deterministically here — before any Provider I/O — instead of silently
        // executing under the new revision. (Claimed jobs never hold a Gateway link yet; linked
        // executions keep converging on their own lineage via convergeStuckLinked.)
        var job = jobs.findJob(claimed.jobId(), claimed.orgId());
        if (job == null) {
            // P1-1: no job row exists, so there is no (job, attempt) pair to terminalize; return
            // without Provider I/O. Attempt rows without a job row are never dispatched.
            return;
        }
        final AdvisorJobMapper.ProfileRow profile;
        if (job.advisorProfileId() != null) {
            profile = jobs.findProfileById(claimed.orgId(), job.advisorProfileId());
        } else {
            profile = jobs.findProfileByOrgVersion(claimed.orgId(), job.advisorProfileVersion());
        }
        if (profile == null) {
            // P1-1 atomic terminal convergence before any Provider I/O.
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "PROFILE_MISSING");
            return;
        }
        if (!"ACTIVE".equals(profile.status())) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "PROFILE_SUPERSEDED");
            return;
        }
        // Exact profile-bound credential (legacy LIMIT 1 fallback for pre-V26 rows only), then
        // enforce principal match against the BOUND profile.
        var credential = jobs.findCredentialForProfile(claimed.orgId(), profile.id());
        if (credential == null && job.advisorProfileId() == null) {
            credential = jobs.findBoundInternalCredential(claimed.orgId());
        }
        if (credential == null && job.advisorProfileId() == null) {
            credential = jobs.findInternalCredential(claimed.orgId());
        }
        if (credential == null) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "IDENTITY_MISSING");
            return;
        }
        if (credential.projectId() != profile.projectId()
                || !credential.financialScopeType().equals(profile.financialScopeType())
                || credential.financialScopeId() != profile.financialScopeId()
                || !credential.budgetEnforcementMode().equals(profile.budgetEnforcementMode())) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "PROFILE_CREDENTIAL_MISMATCH");
            return;
        }
        var logicalModelId = jobs.findLogicalModelOf(profile.providerModelId());
        if (logicalModelId == null) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "MODEL_NOT_FOUND");
            return;
        }
        // P1 frozen evidence: the model receives exactly the snapshotted facts. The stored
        // fingerprint is recomputed here, before any Provider I/O; mutable subject rows are never
        // re-read for prompt construction.
        var snapshot = jobs.findEvidenceSnapshot(claimed.jobId(), claimed.orgId());
        if (snapshot == null || !snapshot.evidenceFingerprint().equals(job.evidenceFingerprint())
                || !EvidenceFingerprint.verify(snapshot, objectMapper)) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "EVIDENCE_INTEGRITY_FAILED");
            return;
        }
        final java.util.Set<String> allowedRefs;
        try {
            allowedRefs = prompts.snapshotDerivedRefs(snapshot);
        } catch (RuntimeException ex) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "EVIDENCE_INTEGRITY_FAILED");
            return;
        }
        var jobRefs = prompts.parseRefs(job.evidenceRefsJson());
        if (!allowedRefs.equals(jobRefs)) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "EVIDENCE_INTEGRITY_FAILED");
            return;
        }
        var prompt = prompts.buildPrompt(snapshot);
        if (prompt == null) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "SUBJECT_UNAVAILABLE");
            return;
        }
        var principal = new GatewayPrincipal(credential.id(), claimed.orgId(), profile.projectId(),
                "SERVICE", null, credential.serviceIdentityId(), credential.financialScopeType(),
                credential.financialScopeId(), credential.budgetEnforcementMode());
        var command = new GatewayRequestService.AuthorizeCommand(principal, logicalModelId,
                prompt.getBytes(StandardCharsets.UTF_8),
                "advisor:" + claimed.jobId() + ":" + claimed.attemptNo(),
                ADVISOR_MAX_TOKENS, false);
        final GatewayRequestOrchestrator.PreparedDispatch prepared;
        try {
            prepared = orchestrator.prepareInitial(command, false).block(Duration.ofMinutes(2));
        } catch (RuntimeException ex) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), mapFailureCode(ex));
            return;
        }
        if (prepared == null || prepared.dispatch() == null) {
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "ROUTING_NOT_READY");
            return;
        }
        var dispatch = prepared.dispatch();
        var linked = transactions.execute(status -> {
            if (jobs.linkGateway(claimed.jobId(), claimed.token(), "DISPATCHING", dispatch.requestId(),
                    clock.instant().plus(LEASE)) != 1) {
                status.setRollbackOnly();
                return false;
            }
            if (jobs.linkAttempt(claimed.jobId(), claimed.attemptNo(), dispatch.requestId()) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (linked == null || !linked) {
            return;
        }
        final ProviderChatCompletion completion;
        try {
            var context = buildContext(principal, dispatch);
            lifecycleService.beginUpstream(dispatch.requestId(), claimed.orgId(),
                    dispatch.routeAttemptId()).block(Duration.ofMinutes(1));
            ProviderChatAdapter adapter = adapterRegistry.require(dispatch.adapterCode());
            var chatCommand = new ChatCompletionCommand("advisor",
                    List.of(new ChatCompletionCommand.Message("user", prompt)),
                    ADVISOR_MAX_TOKENS, false);
            completion = adapter.complete(context, chatCommand).block(Duration.ofMinutes(9));
        } catch (RuntimeException ex) {
            var contextFailure = contextResolutionFailure(ex);
            if (contextFailure != null) {
                try {
                    orchestrator.failBeforeProviderIo(prepared, contextFailure)
                            .block(Duration.ofMinutes(1));
                } catch (RuntimeException nested) {
                    LOG.warn("Advisor pre-dispatch context failure convergence failed for job {}",
                            claimed.jobId(), nested);
                }
                terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(),
                        mapFailureCode(ex));
            } else {
                failAfterDispatch(claimed, dispatch, ex);
            }
            return;
        }
        if (completion == null) {
            failAfterDispatch(claimed, dispatch,
                    new ProviderExecutionException(
                            com.aicostops.gateway.provider.ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                            com.aicostops.gateway.provider.ProviderSafetyReason.UNKNOWN_POST_DISPATCH,
                            com.aicostops.gateway.provider.ProviderHealthSignal.QUALIFYING_FAILURE,
                            null, null, true, null));
            return;
        }
        usageFinalization.finalizeSuccess(dispatch.requestId(), claimed.orgId(),
                dispatch.routeAttemptId(),
                GatewayUsageObservation.fromCompletion(completion, null))
                .block(Duration.ofMinutes(1));
        var narrative = completion.choices().isEmpty() ? ""
                : String.valueOf(completion.choices().get(0).content());
        final AdvisorNarrativeValidator.ValidatedNarrative validated;
        try {
            validated = narratives.validateNarrative(narrative, allowedRefs);
        } catch (IllegalArgumentException ex) {
            // P1-1 atomic terminal convergence; Provider I/O already happened, but job+attempt
            // must still commit together with row-count asserts.
            terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), "INVALID_RESPONSE");
            return;
        }
        // P1-1 atomic terminal convergence: explanation insert + job COMPLETED + attempt
        // COMPLETED share one short transaction with row-count asserts; any mismatch rolls
        // back all three. Provider I/O (adapter.complete + usage finalization above) never
        // occurs inside this transaction.
        terminalizeCompletedWithExplanation(claimed.orgId(), claimed.jobId(), claimed.token(),
                claimed.attemptNo(), validated.summary(), validated.drivers(),
                toJson(validated.actions()), toJson(validated.warnings()), toJson(validated.refs()));
    }

    private void failAfterDispatch(ClaimedJob claimed,
            GatewayRequestService.DispatchResult dispatch, RuntimeException ex) {
        try {
            var timeout = isTimeout(ex);
            usageFinalization.finalizeFailure(dispatch.requestId(), claimed.orgId(),
                    dispatch.routeAttemptId(),
                    GatewayUsageObservation.noUsage(null).withDispatched(true),
                    timeout ? TransportFailure.TIMED_OUT : TransportFailure.FAILED)
                    .block(Duration.ofMinutes(1));
        } catch (RuntimeException nested) {
            LOG.warn("Advisor failure finalization failed for job {}", claimed.jobId(), nested);
        }
        var code = mapFailureCode(ex);
        // P1-1 atomic terminal convergence after dispatch; usage finalization above never
        // runs inside the terminal transaction.
        terminalizeFailed(claimed.jobId(), claimed.token(), claimed.attemptNo(), code);
    }

    private void convergeStuckLinked() {
        var now = clock.instant();
        for (var job : jobs.stuckLinked(now)) {
            try {
                var state = job.gatewayRequestId() == null ? null
                        : jobs.findGatewayRequestState(job.gatewayRequestId());
                if (state == null || TERMINAL_PRE_DISPATCH.contains(state)) {
                    // P1-1 stuck-linked convergence: job + its linked current attempt terminalize
                    // together in one short transaction; never job FAILED + attempt RUNNING.
                    // Blind redispatch is still absolutely forbidden here.
                    terminalizeFailedAt(job.id(), job.claimToken(), job.attemptCount(),
                            state == null ? "GATEWAY_LOST" : state, now);
                } else if (TERMINAL_POST_DISPATCH.contains(state)) {
                    terminalizeFailedAt(job.id(), job.claimToken(), job.attemptCount(),
                            "BILLABLE_UNCERTAIN", now);
                } else if ("TRANSPORT_COMPLETED".equals(state)) {
                    terminalizeFailedAt(job.id(), job.claimToken(), job.attemptCount(),
                            "EXPLANATION_PENDING", now);
                } else {
                    jobs.extendLease(job.id(), now.plus(LEASE));
                }
            } catch (RuntimeException ex) {
                LOG.warn("Advisor convergence failed for job {}", job.id(), ex);
            }
        }
    }

    /**
     * Builds the model prompt exclusively from the verified immutable evidence snapshot: every
     * fact carries its server-generated ID, drivers are the persisted deterministic analysis
     * (never inferred), and budget-risk inputs plus classification arrive precomputed. Mutable
     * subject rows are never consulted here.
     */

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor JSON encoding failed", ex);
        }
    }

    private ProviderCallContext buildContext(GatewayPrincipal principal,
            GatewayRequestService.DispatchResult result) {
        return contextResolver.resolve(principal, result);
    }

    private static ProviderContextResolutionException contextResolutionFailure(Throwable error) {
        var current = reactor.core.Exceptions.unwrap(error);
        while (current != null) {
            if (current instanceof ProviderContextResolutionException contextFailure) return contextFailure;
            current = current.getCause();
        }
        return null;
    }

    private String mapFailureCode(RuntimeException ex) {
        var current = ex;
        while (current != null) {
            if (current instanceof ProviderExecutionException provider) {
                return switch (provider.safetyReason()) {
                    case DNS_PRE_CONNECT -> "DNS_FAILED";
                    case CONNECT_REFUSED_PRE_WRITE, CONNECT_TIMEOUT_PRE_WRITE -> "CONNECTION_TIMEOUT";
                    case TLS_HANDSHAKE_PRE_HTTP_WRITE -> "TLS_FAILED";
                    case HTTP_RESPONSE_RECEIVED -> provider.httpStatus() != null && provider.httpStatus() == 429
                            ? "RATE_LIMITED" : "PROVIDER_UNAVAILABLE";
                    default -> provider.safetyOutcome()
                            == com.aicostops.gateway.provider.ProviderSafetyOutcome.BILLABLE_POSSIBLE
                            ? "BILLABLE_UNCERTAIN" : "PROVIDER_UNAVAILABLE";
                };
            }
            if (current instanceof GatewayErrorException gateway) {
                if (gateway.code() == GatewayErrorCode.GATEWAY_BUDGET_EXHAUSTED) return "BUDGET_REJECTED";
                if (gateway.code() == GatewayErrorCode.GATEWAY_FORBIDDEN) return "ROUTING_NOT_READY";
                if (gateway.code() == GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT) return "PROVIDER_TIMEOUT";
            }
            current = current.getCause() instanceof RuntimeException runtime ? runtime : null;
        }
        if (isTimeout(ex)) return "PROVIDER_TIMEOUT";
        return "PROVIDER_UNAVAILABLE";
    }

    private static boolean isTimeout(Throwable ex) {
        var current = ex;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    private byte[] randomBytes(int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    public record ClaimedJob(long jobId, long orgId, String token, int attemptNo,
            String subjectType, long subjectId, String evidenceRefsJson) {
    }

}
