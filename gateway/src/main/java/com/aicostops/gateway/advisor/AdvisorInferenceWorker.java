package com.aicostops.gateway.advisor;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService.TransportFailure;
import com.aicostops.gateway.metering.GatewayUsageObservation;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatAdapterRegistry;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
    private final GatewayReadMapper readMapper;
    private final GatewayRequestOrchestrator orchestrator;
    private final GatewayRequestLifecycleService lifecycleService;
    private final ProviderChatAdapterRegistry adapterRegistry;
    private final ProviderCredentialDecryptor credentialDecryptor;
    private final GatewayUsageFinalizationService usageFinalization;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public AdvisorInferenceWorker(
            AdvisorJobMapper jobs,
            GatewayReadMapper readMapper,
            GatewayRequestOrchestrator orchestrator,
            GatewayRequestLifecycleService lifecycleService,
            ProviderChatAdapterRegistry adapterRegistry,
            ProviderCredentialDecryptor credentialDecryptor,
            GatewayUsageFinalizationService usageFinalization,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.readMapper = readMapper;
        this.orchestrator = orchestrator;
        this.lifecycleService = lifecycleService;
        this.adapterRegistry = adapterRegistry;
        this.credentialDecryptor = credentialDecryptor;
        this.usageFinalization = usageFinalization;
        this.objectMapper = objectMapper;
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

    private void execute(ClaimedJob claimed) {
        try {
            runGoverned(claimed);
        } catch (RuntimeException ex) {
            LOG.warn("Advisor job {} failed", claimed.jobId(), ex);
            var code = mapFailureCode(ex);
            try {
                jobs.markFailed(claimed.jobId(), claimed.token(), code, clock.instant());
            } catch (RuntimeException nested) {
                LOG.warn("Advisor job {} failure could not be recorded", claimed.jobId(), nested);
            }
            try {
                jobs.failAttempt(claimed.jobId(), claimed.attemptNo(), code);
            } catch (RuntimeException ignored) {
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
            jobs.markFailed(claimed.jobId(), claimed.token(), "JOB_MISSING", clock.instant());
            return;
        }
        final AdvisorJobMapper.ProfileRow profile;
        if (job.advisorProfileId() != null) {
            profile = jobs.findProfileById(claimed.orgId(), job.advisorProfileId());
        } else {
            profile = jobs.findProfileByOrgVersion(claimed.orgId(), job.advisorProfileVersion());
        }
        if (profile == null) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "PROFILE_MISSING", clock.instant());
            return;
        }
        if (!"ACTIVE".equals(profile.status())) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "PROFILE_SUPERSEDED", clock.instant());
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
            jobs.markFailed(claimed.jobId(), claimed.token(), "IDENTITY_MISSING", clock.instant());
            return;
        }
        if (credential.projectId() != profile.projectId()
                || !credential.financialScopeType().equals(profile.financialScopeType())
                || credential.financialScopeId() != profile.financialScopeId()
                || !credential.budgetEnforcementMode().equals(profile.budgetEnforcementMode())) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "PROFILE_CREDENTIAL_MISMATCH", clock.instant());
            return;
        }
        var logicalModelId = jobs.findLogicalModelOf(profile.providerModelId());
        if (logicalModelId == null) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "MODEL_NOT_FOUND", clock.instant());
            return;
        }
        // P1 frozen evidence: the model receives exactly the snapshotted facts. The stored
        // fingerprint is recomputed here, before any Provider I/O; mutable subject rows are never
        // re-read for prompt construction.
        var snapshot = jobs.findEvidenceSnapshot(claimed.jobId(), claimed.orgId());
        if (snapshot == null || !snapshot.evidenceFingerprint().equals(job.evidenceFingerprint())
                || !EvidenceFingerprint.verify(snapshot, objectMapper)) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "EVIDENCE_INTEGRITY_FAILED", clock.instant());
            return;
        }
        final java.util.Set<String> allowedRefs;
        try {
            allowedRefs = snapshotDerivedRefs(snapshot);
        } catch (RuntimeException ex) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "EVIDENCE_INTEGRITY_FAILED", clock.instant());
            return;
        }
        var jobRefs = parseRefs(job.evidenceRefsJson());
        if (!allowedRefs.equals(jobRefs)) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "EVIDENCE_INTEGRITY_FAILED", clock.instant());
            return;
        }
        var prompt = buildPrompt(snapshot);
        if (prompt == null) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "SUBJECT_UNAVAILABLE", clock.instant());
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
            jobs.markFailed(claimed.jobId(), claimed.token(), mapFailureCode(ex), clock.instant());
            return;
        }
        if (prepared == null || prepared.dispatch() == null) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "ROUTING_NOT_READY", clock.instant());
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
            failAfterDispatch(claimed, dispatch, ex);
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
        final ValidatedNarrative validated;
        try {
            validated = validateNarrative(narrative, allowedRefs);
        } catch (IllegalArgumentException ex) {
            jobs.markFailed(claimed.jobId(), claimed.token(), "INVALID_RESPONSE", clock.instant());
            try {
                jobs.failAttempt(claimed.jobId(), claimed.attemptNo(), "INVALID_RESPONSE");
            } catch (RuntimeException ignored) {
            }
            return;
        }
        jobs.insertExplanation(claimed.orgId(), claimed.jobId(), claimed.attemptNo(),
                validated.summary(), validated.drivers(), toJson(validated.actions()),
                toJson(validated.warnings()), toJson(validated.refs()), clock.instant());
        jobs.markCompleted(claimed.jobId(), claimed.token(), clock.instant());
        try {
            jobs.completeAttempt(claimed.jobId(), claimed.attemptNo());
        } catch (RuntimeException ignored) {
        }
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
        jobs.markFailed(claimed.jobId(), claimed.token(), code, clock.instant());
        try {
            jobs.failAttempt(claimed.jobId(), claimed.attemptNo(), code);
        } catch (RuntimeException ignored) {
        }
    }

    private void convergeStuckLinked() {
        var now = clock.instant();
        for (var job : jobs.stuckLinked(now)) {
            try {
                var state = job.gatewayRequestId() == null ? null
                        : jobs.findGatewayRequestState(job.gatewayRequestId());
                if (state == null || TERMINAL_PRE_DISPATCH.contains(state)) {
                    jobs.markFailed(job.id(), job.claimToken(),
                            state == null ? "GATEWAY_LOST" : state, now);
                } else if (TERMINAL_POST_DISPATCH.contains(state)) {
                    jobs.markFailed(job.id(), job.claimToken(), "BILLABLE_UNCERTAIN", now);
                } else if ("TRANSPORT_COMPLETED".equals(state)) {
                    jobs.markFailed(job.id(), job.claimToken(), "EXPLANATION_PENDING", now);
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
    private String buildPrompt(AdvisorJobMapper.SnapshotRow snapshot) {
        try {
            var factsNode = objectMapper.readTree(snapshot.factsJson());
            var driversNode = objectMapper.readTree(snapshot.driversJson());
            var summaryNode = objectMapper.readTree(snapshot.summaryJson());
            if (factsNode == null || !factsNode.isArray() || factsNode.isEmpty()
                    || driversNode == null || !driversNode.isArray()
                    || summaryNode == null || !summaryNode.isObject()) {
                return null;
            }
            var envelope = objectMapper.createObjectNode();
            envelope.put("schema_version", snapshot.schemaVersion());
            envelope.put("subject", snapshot.subjectType());
            envelope.put("subject_id", snapshot.subjectId());
            envelope.put("currency", snapshot.currency());
            var factsArray = envelope.putArray("facts");
            for (var fact : factsNode) {
                var id = fact.path("factId").asText("");
                var label = fact.path("label").asText("");
                var amount = fact.path("amount").asText("");
                if (id.isBlank() || label.isBlank() || amount.isBlank()) {
                    return null;
                }
                var node = objectMapper.createObjectNode();
                node.put("id", id);
                node.put("label", label);
                node.put("amount", amount);
                node.put("currency", fact.path("currency").asText(snapshot.currency()));
                factsArray.add(node);
            }
            var driversArray = envelope.putArray("drivers");
            for (var driver : driversNode) {
                var id = driver.path("id").asText("");
                var dimension = driver.path("dimension").asText("");
                var key = driver.path("key").asText("");
                var delta = driver.path("deltaAmount").asText("");
                if (id.isBlank() || dimension.isBlank() || key.isBlank() || delta.isBlank()) {
                    return null;
                }
                var node = objectMapper.createObjectNode();
                node.put("id", id);
                node.put("dimension", dimension);
                node.put("key", key);
                node.put("deltaAmount", delta);
                node.put("currency", driver.path("currency").asText(snapshot.currency()));
                driversArray.add(node);
            }
            envelope.put("forecastSummary", summaryNode.path("forecastSummary").asText(""));
            envelope.put("savingsSummary", summaryNode.path("savingsSummary").asText(""));
            var instruction = new StringBuilder("Explain the cost facts briefly. Reply with JSON object "
                    + "containing only: summary, driversExplanation, recommendedActions (array of strings), "
                    + "warnings (array of strings), factReferences (array of strings). "
                    + "Never invent amounts; never include money fields. "
                    + "factReferences may only contain the fact/driver IDs listed above. "
                    + "Drivers are deterministic analysis results; never invent drivers.");
            if ("BUDGET_RISK".equals(snapshot.subjectType())) {
                final JsonNode risk;
                try {
                    risk = objectMapper.readTree(summaryNode.path("budgetRiskSummary").asText(""));
                } catch (Exception ex) {
                    return null;
                }
                if (risk == null || !risk.isObject() || risk.path("risk").asText("").isBlank()) {
                    return null;
                }
                var riskNode = objectMapper.createObjectNode();
                for (var field : List.of("scope", "actual", "committed", "reservations",
                        "forecastFuture", "immediate", "projected", "total", "risk")) {
                    riskNode.put(field, risk.path(field).asText(""));
                }
                envelope.set("budgetRisk", riskNode);
                instruction.append(" The budget risk inputs and classification are precomputed; "
                        + "explain them only and never recompute projected spend or risk.");
            } else {
                envelope.put("budgetRiskSummary", summaryNode.path("budgetRiskSummary").asText(""));
            }
            envelope.put("instruction", instruction.toString());
            return objectMapper.writeValueAsString(envelope);
        } catch (RuntimeException ex) {
            LOG.warn("Advisor prompt build failed for snapshot {}", snapshot.id(), ex);
            return null;
        }
    }

    private Set<String> knownRefs(ClaimedJob claimed) {
        return parseRefs(claimed.evidenceRefsJson());
    }

    private Set<String> parseRefs(String refsJson) {
        try {
            var refs = new java.util.HashSet<String>();
            var raw = refsJson == null ? "[]" : refsJson;
            var node = objectMapper.readTree(raw);
            if (node != null && node.isArray()) {
                for (var item : node) refs.add(item.stringValue());
            }
            return Set.copyOf(refs);
        } catch (Exception ex) {
            return Set.of();
        }
    }

    private Set<String> snapshotDerivedRefs(AdvisorJobMapper.SnapshotRow snapshot) {
        try {
            var refs = new java.util.HashSet<String>();
            var factsNode = objectMapper.readTree(snapshot.factsJson());
            var driversNode = objectMapper.readTree(snapshot.driversJson());
            if (factsNode == null || !factsNode.isArray() || driversNode == null || !driversNode.isArray()) {
                throw new IllegalArgumentException("Advisor snapshot refs are unreadable");
            }
            for (var item : factsNode) {
                var id = item.path("factId").asText("");
                if (id.isBlank()) {
                    throw new IllegalArgumentException("Advisor snapshot fact ref is missing");
                }
                refs.add(id);
            }
            for (var item : driversNode) {
                var id = item.path("id").asText("");
                if (id.isBlank()) {
                    throw new IllegalArgumentException("Advisor snapshot driver ref is missing");
                }
                refs.add(id);
            }
            return Set.copyOf(refs);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Advisor snapshot refs are unreadable", ex);
        }
    }

    private ValidatedNarrative validateNarrative(String narrative, Set<String> knownRefs) {
        if (narrative == null || narrative.isBlank() || narrative.length() > 32768) {
            throw new IllegalArgumentException("Advisor narrative is missing or oversized.");
        }
        final ObjectNode tree;
        try {
            var parsed = objectMapper.readTree(narrative);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Not an object.");
            tree = (ObjectNode) parsed;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Advisor narrative is not JSON.", ex);
        }
        var allowed = Set.of("summary", "driversExplanation", "recommendedActions", "warnings",
                "factReferences");
        for (var entry : tree.properties()) {
            var field = entry.getKey().toLowerCase(Locale.ROOT);
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException("Advisor field not allowed: " + entry.getKey());
            }
            if (field.contains("amount") || field.contains("cost") || field.contains("saving")
                    || field.contains("price") || field.contains("budget") || field.contains("forecast")) {
                throw new IllegalArgumentException("Advisor must not carry money: " + entry.getKey());
            }
        }
        var summary = text(tree, "summary");
        if (summary.isBlank()) throw new IllegalArgumentException("Advisor summary required.");
        var refs = strings(tree, "factReferences");
        for (var ref : refs) {
            if (!knownRefs.contains(ref)) throw new IllegalArgumentException("Unknown fact ref: " + ref);
        }
        return new ValidatedNarrative(sanitize(summary, 2000),
                sanitize(text(tree, "driversExplanation"), 4000),
                strings(tree, "recommendedActions").stream().map(v -> sanitize(v, 500)).toList(),
                strings(tree, "warnings").stream().map(v -> sanitize(v, 500)).toList(), refs);
    }

    private static String text(ObjectNode tree, String field) {
        var node = tree.get(field);
        if (node == null || node.isNull()) return "";
        if (!node.isString()) throw new IllegalArgumentException("Not text: " + field);
        return node.stringValue();
    }

    private static List<String> strings(ObjectNode tree, String field) {
        var node = tree.get(field);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("Not a list: " + field);
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isString()) throw new IllegalArgumentException("Not text item: " + field);
            values.add(item.stringValue());
        }
        return List.copyOf(values);
    }

    private static String sanitize(String value, int max) {
        var cleaned = value.strip().replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor JSON encoding failed", ex);
        }
    }

    private ProviderCallContext buildContext(GatewayPrincipal principal,
            GatewayRequestService.DispatchResult result) {
        var credential = credentialDecryptor.decrypt(principal.organizationId(), result.providerAccountId());
        var profile = readMapper.findActiveConnectionProfile(
                principal.organizationId(), result.providerAccountId());
        final String credentialType;
        final byte[] secret;
        if (profile != null && "NONE".equals(profile.authType())) {
            credentialType = "NONE";
            secret = null;
        } else {
            credentialType = credential.credentialType();
            secret = credential.secret();
        }
        return new ProviderCallContext(result.adapterCode(), result.providerAccountId(),
                result.providerModelId(), result.providerModelName(), result.pricingVersionId(),
                result.currency(), result.baseUrl(), credentialType, secret, result.routeDecisionId(),
                result.providerConnectionProfileId(), result.completionPath(), result.protocolCode(),
                result.networkPolicy(), profile == null ? null : profile.authHeaderName());
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

    private record ValidatedNarrative(String summary, String drivers, List<String> actions,
            List<String> warnings, List<String> refs) {
    }
}
