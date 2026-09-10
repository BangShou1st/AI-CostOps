package com.aicostops.advisor.application;

import com.aicostops.advisor.infrastructure.AdvisorMapper;
import com.aicostops.advisor.infrastructure.AdvisorMapper.JobRow;
import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Governed AI Advisor job lifecycle (M18 V3).
 *
 * <p>Durable states PENDING -&gt; CLAIMED -&gt; DISPATCHING -&gt; RUNNING -&gt;
 * COMPLETED, any -&gt; FAILED. The claim commits before any Provider I/O;
 * retries are explicit append-only attempts with fresh Gateway lineage.
 */
@Service
public class AdvisorService {

    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private static final Set<String> SUBJECT_TYPES = Set.of("ANOMALY", "FORECAST", "BUDGET_RISK", "SAVINGS");

    private final AuthorizationContextService authorizationContexts;
    private final AdvisorMapper mapper;
    private final AdvisorOutputValidator outputValidator;
    private final com.aicostops.intelligence.application.CostIntelligenceService intelligence;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public AdvisorService(
            AuthorizationContextService authorizationContexts,
            AdvisorMapper mapper,
            AdvisorOutputValidator outputValidator,
            com.aicostops.intelligence.application.CostIntelligenceService intelligence,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.outputValidator = outputValidator;
        this.intelligence = intelligence;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ProfileResponse profile(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        var row = mapper.findActiveProfile(context.organizationId());
        if (row == null) {
            throw notFound("Advisor profile is not configured.");
        }
        return profileResponse(row);
    }

    @Transactional
    public ProfileResponse updateProfile(AuthenticatedUser user, UpdateProfileRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_MANAGE");
        // P1: org-visible provider model (global OR same-org private, ACTIVE). Never cross-org private.
        if (!"ACTIVE".equals(mapper.findOrgVisibleProviderModelStatus(request.providerModelId(),
                context.organizationId()))) {
            throw validationFailed("Advisor provider model must be ACTIVE and visible to this organization.");
        }
        if (!"ACTIVE".equals(mapper.findProjectStatus(request.projectId(), context.organizationId()))) {
            throw validationFailed("Advisor project must be ACTIVE in this organization.");
        }
        var scope = request.financialScopeType() == null ? "" : request.financialScopeType();
        if (!scope.equals("PROJECT") && !scope.equals("TEAM") && !scope.equals("COST_CENTER")) {
            throw validationFailed("Financial scope type must be PROJECT, TEAM or COST_CENTER.");
        }
        // P1: financialScopeId must belong to current org with matching type and ACTIVE status.
        validateFinancialScopeOwnership(context.organizationId(), scope, request.financialScopeId());
        var budgetMode = request.budgetEnforcementMode() == null ? "" : request.budgetEnforcementMode();
        if (!budgetMode.equals("REQUIRED") && !budgetMode.equals("OPTIONAL")) {
            throw validationFailed("Budget enforcement mode must be REQUIRED or OPTIONAL.");
        }
        var now = clock.instant();
        var previous = mapper.findActiveProfile(context.organizationId());
        if (previous != null) {
            mapper.retireProfile(previous.id(), context.organizationId(), now);
        }
        var version = mapper.nextProfileVersion(context.organizationId());
        mapper.insertProfile(context.organizationId(), version, request.providerModelId(),
                request.projectId(), scope, request.financialScopeId(), budgetMode,
                context.organizationMemberId(), now);
        var created = mapper.findProfile(mapper.lastInsertId(), context.organizationId());
        // P0: rotate INTERNAL_SYSTEM credential so execution principal exactly matches new ACTIVE profile.
        rotateInternalIdentity(context.organizationId(), created.id(), request.projectId(), scope,
                request.financialScopeId(), budgetMode, request.providerModelId(), now);
        // P0 frozen-job principle: undispatched work bound to the superseded revision (or legacy
        // unbound rows) deterministically fails here so it can never silently execute under v2.
        // Already-linked executions keep converging on their own Gateway lineage instead.
        if (previous != null) {
            var superseded = mapper.supersedePendingJobs(context.organizationId(), previous.id(), now);
            // P1-1 atomic terminal convergence: the current PENDING child attempt of every
            // superseded job must fail in the same DB transaction (never job FAILED + attempt PENDING).
            // Provider I/O never occurs here. Both updates share this @Transactional boundary,
            // so any attempt-update failure rolls back the job update as well.
            var supersededAttempts = mapper.supersedePendingAttempts(context.organizationId(), previous.id());
            if (superseded != supersededAttempts) {
                throw new IllegalStateException("Advisor supersede left job/attempt split state");
            }
            audit.append("AI_ADVISOR_PROFILE_UPDATED", context.organizationId(), user.userId(),
                    "ADVISOR_PROFILE", created.id(),
                    Map.of("version", created.version(), "supersededJobs", superseded));
        } else {
            audit.append("AI_ADVISOR_PROFILE_UPDATED", context.organizationId(), user.userId(),
                    "ADVISOR_PROFILE", created.id(), Map.of("version", created.version()));
        }
        return profileResponse(created);
    }

    private void validateFinancialScopeOwnership(long organizationId, String scopeType, long scopeId) {
        final String status = switch (scopeType) {
            case "PROJECT" -> mapper.findProjectStatus(scopeId, organizationId);
            case "TEAM" -> mapper.findTeamStatus(scopeId, organizationId);
            case "COST_CENTER" -> mapper.findCostCenterStatus(scopeId, organizationId);
            default -> null;
        };
        if (!"ACTIVE".equals(status)) {
            throw validationFailed("Advisor financial scope must be ACTIVE in this organization.");
        }
    }

    /**
     * P1: server-generated evidence. The client supplies only the subject identity
     * (subjectType + subjectId); the backend loads deterministic facts by current org and
     * builds/fingerprints the envelope itself. Client-supplied money/drivers are never trusted
     * (legacy fields, if present, are ignored) so a malicious client cannot inject amounts or
     * fake fact references. Foreign-org or missing subjects are rejected.
     */
    @Transactional
    public JobResponse requestExplanation(AuthenticatedUser user, ExplanationRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        var profile = mapper.findActiveProfile(context.organizationId());
        if (profile == null) {
            throw validationFailed("Advisor profile is not configured.");
        }
        if (request.subjectType() == null || !SUBJECT_TYPES.contains(request.subjectType())) {
            throw validationFailed("Subject type must be ANOMALY, FORECAST, BUDGET_RISK or SAVINGS.");
        }
        var envelope = buildServerEnvelope(context.organizationId(), request.subjectType(), request.subjectId());
        var fingerprint = AdvisorEvidence.fingerprint(envelope);
        var now = clock.instant();
        // Fact references cover money facts plus deterministic driver references so the model can
        // cite drivers without failing output validation; all IDs are server-generated.
        var allRefs = new java.util.ArrayList<>(envelope.factReferenceIds());
        allRefs.addAll(driverReferenceIds(envelope));
        final String refsJson;
        try {
            refsJson = objectMapper.writeValueAsString(allRefs);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence references are unavailable", ex);
        }
        // P0: the job is bound to its exact profile revision (id + version); the worker must
        // never substitute the later ACTIVE revision.
        mapper.insertJob(context.organizationId(), context.organizationMemberId(),
                envelope.subjectType(), envelope.subjectId(), fingerprint, refsJson, profile.version(),
                profile.id(), now);
        var jobId = mapper.lastInsertId();
        // P1: persist the immutable bounded evidence snapshot the model will actually receive.
        // The worker verifies the fingerprint before any Provider I/O instead of re-reading
        // mutable subject rows.
        try {
            mapper.insertEvidenceSnapshot(context.organizationId(), jobId, envelope.schemaVersion(),
                    envelope.subjectType(), envelope.subjectId(), envelope.currency(),
                    snapshotFactsJson(envelope),
                    snapshotDriversJson(envelope),
                    objectMapper.writeValueAsString(snapshotSummary(envelope)), fingerprint,
                    envelope.generatedAt(), now);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence snapshot is unavailable", ex);
        }
        mapper.insertAttempt(context.organizationId(), jobId, 1, now);
        audit.append("AI_ADVISOR_EXPLANATION_REQUESTED", context.organizationId(), user.userId(),
                "ADVISOR_JOB", jobId,
                Map.of("subject", envelope.subjectType(), "attempt", 1));
        return jobResponse(requireJob(context.organizationId(), jobId));
    }

    public JobResponse explanation(AuthenticatedUser user, long jobId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        return jobResponse(requireJob(context.organizationId(), jobId));
    }

    /** Explicit retry: new append-only attempt with fresh Gateway lineage. */
    @Transactional
    public JobResponse retry(AuthenticatedUser user, long jobId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        var job = requireJob(context.organizationId(), jobId);
        if (!"COMPLETED".equals(job.status()) && !"FAILED".equals(job.status())) {
            throw stateConflict("Only terminal advisor jobs can be retried.");
        }
        if (mapper.reopenForRetry(jobId, context.organizationId()) != 1) {
            throw new IllegalStateException("Advisor job could not be reopened");
        }
        var reopened = requireJob(context.organizationId(), jobId);
        mapper.insertAttempt(context.organizationId(), jobId, reopened.attemptCount(), clock.instant());
        audit.append("AI_ADVISOR_EXPLANATION_REQUESTED", context.organizationId(), user.userId(),
                "ADVISOR_JOB", jobId, Map.of("retry", true, "attempt", reopened.attemptCount()));
        return jobResponse(reopened);
    }

    /** Worker claim: locks one eligible job, assigns fencing token + lease, commits. */
    @Transactional
    public ClaimedJob claimNext(String workerId) {
        var now = clock.instant();
        var candidate = mapper.claimEligibleAny(now);
        if (candidate == null) {
            return null;
        }
        // P1-1: claim_token is CHAR(40); keep fencing entropy while respecting the column bound.
        // 16 random bytes (32 hex) plus up to 8 worker suffix chars fits exactly in 40.
        var token = HexFormat.of().formatHex(randomBytes(16)) + workerIdSuffix(workerId);
        if (mapper.markClaimed(candidate.id(), candidate.orgId(), token,
                now.plus(CLAIM_LEASE), now) != 1) {
            return null;
        }
        return new ClaimedJob(candidate.id(), candidate.orgId(), token, candidate.attemptCount());
    }

    /** Links the governed Gateway execution; fencing token must match. */
    @Transactional
    public void linkGateway(long jobId, long organizationId, String token, long gatewayRequestId) {
        if (mapper.linkGateway(jobId, organizationId, token, "DISPATCHING", gatewayRequestId,
                clock.instant().plus(CLAIM_LEASE)) != 1) {
            throw stateConflict("Advisor job claim is not held by this worker.");
        }
        var job = requireJob(organizationId, jobId);
        if (mapper.linkAttempt(organizationId, jobId, job.attemptCount(), gatewayRequestId) != 1) {
            throw stateConflict("Advisor attempt link was not recorded.");
        }
    }

    /** Persists only the validated bounded narrative, then completes. */
    @Transactional
    public void complete(long jobId, long organizationId, String token, String rawNarrativeJson,
            Set<String> knownFactReferences) {
        var job = requireJob(organizationId, jobId);
        var validated = outputValidator.validate(rawNarrativeJson, knownFactReferences);
        var now = clock.instant();
        try {
            mapper.insertExplanation(organizationId, jobId, job.attemptCount(), validated.summary(),
                    validated.driversExplanation(),
                    objectMapper.writeValueAsString(validated.recommendedActions()),
                    objectMapper.writeValueAsString(validated.warnings()),
                    objectMapper.writeValueAsString(validated.factReferences()), now);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor explanation could not be persisted", ex);
        }
        if (mapper.markCompleted(jobId, organizationId, token, now) != 1) {
            throw stateConflict("Advisor job claim is not held by this worker.");
        }
        // P1-1 atomic terminal convergence: job COMPLETED + current attempt COMPLETED must
        // commit together. Affected-row mismatch rolls back the whole @Transactional unit
        // (including the explanation insert above). Provider I/O never occurs here.
        if (mapper.completeAttempt(organizationId, jobId, job.attemptCount()) != 1) {
            throw stateConflict("Advisor attempt terminal transition failed; job update rolled back.");
        }
        audit.append("AI_ADVISOR_EXPLANATION_COMPLETED", organizationId, job.requestedBy(),
                "ADVISOR_JOB", jobId, Map.of("attempt", job.attemptCount()));
    }

    @Transactional
    public void fail(long jobId, long organizationId, String token, String failureCode) {
        var job = requireJob(organizationId, jobId);
        if (mapper.markFailed(jobId, organizationId, token, failureCode, clock.instant()) != 1) {
            throw stateConflict("Advisor job claim is not held by this worker.");
        }
        // P1-1 atomic terminal convergence: job FAILED + current attempt FAILED must commit
        // together. Never swallow the attempt update and never ignore affected row counts.
        // Any mismatch rolls back the job update. Provider I/O never occurs here.
        if (mapper.failAttempt(organizationId, jobId, job.attemptCount(), failureCode) != 1) {
            throw stateConflict("Advisor attempt terminal transition failed; job update rolled back.");
        }
        audit.append("AI_ADVISOR_EXPLANATION_FAILED", organizationId, null,
                "ADVISOR_JOB", jobId, Map.of("failureCode", failureCode));
    }

    /**
     * P1-1 explicit transactional terminal transition for (job, current attempt).
     *
     * <p>Verifies fencing (claim token + current attemptNo) and requires exactly one row on
     * both sides; any mismatch rolls back the whole short transaction. Provider I/O must
     * never occur inside this transaction. Success maps to COMPLETED/COMPLETED, failure maps
     * to FAILED/FAILED with the exact safe failure code on the attempt.
     */
    @Transactional
    public void terminalizeAttempt(long jobId, long organizationId, int attemptNo, String claimToken,
            String terminalStatus, String failureCode) {
        var now = clock.instant();
        if ("COMPLETED".equals(terminalStatus)) {
            if (mapper.markCompleted(jobId, organizationId, claimToken, now) != 1) {
                throw stateConflict("Advisor job claim is not held by this worker.");
            }
            if (mapper.completeAttempt(organizationId, jobId, attemptNo) != 1) {
                throw stateConflict("Advisor attempt terminal transition failed; job update rolled back.");
            }
        } else if ("FAILED".equals(terminalStatus)) {
            var code = failureCode == null || failureCode.isBlank() ? "PROVIDER_UNAVAILABLE" : failureCode;
            if (mapper.markFailed(jobId, organizationId, claimToken, code, now) != 1) {
                throw stateConflict("Advisor job claim is not held by this worker.");
            }
            if (mapper.failAttempt(organizationId, jobId, attemptNo, code) != 1) {
                throw stateConflict("Advisor attempt terminal transition failed; job update rolled back.");
            }
        } else {
            throw validationFailed("Terminal status must be COMPLETED or FAILED.");
        }
    }

    /** Crash recovery Case A: reclaim lease-expired jobs with no Gateway link. */
    @Transactional
    public int reclaimOrphans(long organizationId) {
        return mapper.reclaimOrphans(organizationId, clock.instant());
    }

    /**
     * P0: append-only rotation. Every ACTIVE profile revision mints a fresh INTERNAL_SYSTEM
     * credential carrying the profile exact project / scope / budget mode, retires all previous
     * ACTIVE advisor credentials, and grants only the new logical model. Old credentials are never
     * reused for new jobs; history is preserved via REVOKED rows + predecessor linkage.
     */
    private void rotateInternalIdentity(long organizationId, long profileId, long projectId,
            String scopeType, long scopeId, String budgetMode, long providerModelId, Instant now) {
        mapper.ensureAdvisorIdentity(organizationId, now);
        var identityId = mapper.findAdvisorIdentity(organizationId);
        if (identityId == null) {
            throw new IllegalStateException("Advisor service identity is unavailable");
        }
        var logicalModelId = mapper.findOrgVisibleLogicalModelOf(providerModelId, organizationId);
        if (logicalModelId == null) {
            throw validationFailed("Advisor provider model must be ACTIVE and visible to this organization.");
        }
        var predecessors = mapper.listActiveInternalCredentials(organizationId, identityId);
        Long predecessor = predecessors == null || predecessors.isEmpty() ? null : predecessors.get(0);
        mapper.insertBoundInternalCredential(organizationId, internalPrefix(), randomBytes(32),
                identityId, projectId, scopeType, scopeId, budgetMode, predecessor, profileId, now);
        var credentialId = mapper.findBoundInternalCredential(organizationId, identityId);
        if (credentialId == null) {
            credentialId = mapper.findInternalCredential(organizationId, identityId);
        }
        if (credentialId != null) {
            mapper.allowCredentialModel(credentialId, organizationId, logicalModelId, now);
        }
        // Retire every other ACTIVE advisor credential so only the new profile-bound row executes.
        if (predecessors != null) {
            for (var oldId : predecessors) {
                if (credentialId != null && oldId.equals(credentialId)) {
                    continue;
                }
                mapper.revokeInternalCredential(oldId, organizationId, now);
                mapper.disableCredentialModels(oldId, organizationId);
            }
        }
    }

    @Deprecated
    private void ensureInternalIdentity(long organizationId, long projectId, String budgetMode,
            long providerModelId, Instant now) {
        var profile = mapper.findActiveProfile(organizationId);
        if (profile == null) {
            throw new IllegalStateException("Advisor profile is unavailable");
        }
        rotateInternalIdentity(organizationId, profile.id(), projectId, profile.financialScopeType(),
                profile.financialScopeId(), budgetMode, providerModelId, now);
    }

    private String internalPrefix() {
        // gateway_credential.credential_prefix is CHAR(12): keep the generated prefix exactly 12 chars.
        return "aic_" + HexFormat.of().formatHex(randomBytes(4));
    }

    private byte[] randomBytes(int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private String workerIdSuffix(String workerId) {
        var safe = workerId == null ? "w" : workerId.replaceAll("[^A-Za-z0-9]", "");
        return safe.length() <= 8 ? safe : safe.substring(0, 8);
    }

    private JobRow requireJob(long organizationId, long jobId) {
        var job = mapper.findJob(jobId, organizationId);
        if (job == null) {
            throw notFound("Advisor explanation was not found.");
        }
        return job;
    }

    private ProfileResponse profileResponse(AdvisorMapper.ProfileRow row) {
        return new ProfileResponse(row.id(), row.version(), row.providerModelId(), row.projectId(),
                row.financialScopeType(), row.financialScopeId(), row.budgetEnforcementMode(),
                row.status());
    }

    private JobResponse jobResponse(JobRow job) {
        var attempts = mapper.listAttempts(job.orgId(), job.id()).stream()
                .map(a -> new AttemptResponse(a.attemptNo(), a.gatewayRequestId(), a.status(),
                        a.failureCode()))
                .toList();
        var explanation = mapper.findLatestExplanation(job.orgId(), job.id());
        return new JobResponse(job.id(), job.subjectType(), job.subjectId(), job.status(),
                job.attemptCount(), job.gatewayRequestId(), job.failureCode(), attempts,
                explanation == null ? null : new ExplanationResponse(explanation.summary(),
                        explanation.driversExplanation(), explanation.attemptNo()));
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Advisor validation failed", detail);
    }

    private DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Advisor resource not found", detail);
    }

    private DomainException stateConflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Advisor state conflict", detail);
    }

    public record UpdateProfileRequest(
            long providerModelId, long projectId, String financialScopeType,
            long financialScopeId, String budgetEnforcementMode) {
    }

    public record ExplanationRequest(String subjectType, long subjectId) {
        public ExplanationRequest(String subjectType, long subjectId, String currency,
                List<AdvisorEvidence.MoneyFact> facts, List<AdvisorEvidence.Driver> drivers,
                String forecastSummary, String budgetRiskSummary, String savingsSummary) {
            this(subjectType, subjectId);
        }
    }

    private AdvisorEvidence.Envelope buildServerEnvelope(long organizationId, String subjectType, long subjectId) {
        return switch (subjectType) {
            case "ANOMALY" -> {
                var row = mapper.findAnomalySubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                var facts = List.of(
                        new AdvisorEvidence.MoneyFact("anomaly:" + row.id() + ":observed", "observed",
                                row.observedAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("anomaly:" + row.id() + ":baseline", "baseline",
                                row.baselineAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("anomaly:" + row.id() + ":delta", "delta",
                                row.deltaAmount(), row.currency()));
                // P1: drivers come from the persisted deterministic contribution analysis, never
                // from the grain itself; empty stays explicitly empty for the model.
                yield AdvisorEvidence.build("ANOMALY", row.id(), row.currency(), facts,
                        parseAnomalyDrivers(row), "", "", "", clock.instant());
            }
            case "FORECAST" -> {
                var row = mapper.findForecastSubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                var facts = List.of(new AdvisorEvidence.MoneyFact("forecast:" + row.id() + ":projected",
                        "projected", row.projectedAmount(), row.currency()));
                yield AdvisorEvidence.build("FORECAST", row.id(), row.currency(), facts, List.of(),
                        "method=" + row.method(), "", "", clock.instant());
            }
            case "SAVINGS" -> {
                var row = mapper.findSavingsSubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                var facts = List.of(
                        new AdvisorEvidence.MoneyFact("savings:" + row.id() + ":current", "current-cost",
                                row.currentCost(), row.currency()),
                        new AdvisorEvidence.MoneyFact("savings:" + row.id() + ":candidate", "candidate-cost",
                                row.candidateCost(), row.currency()),
                        new AdvisorEvidence.MoneyFact("savings:" + row.id() + ":saving", "potential-saving",
                                row.potentialSaving(), row.currency()));
                yield AdvisorEvidence.build("SAVINGS", row.id(), row.currency(), facts, List.of(),
                        "", "", "", clock.instant());
            }
            case "BUDGET_RISK" -> {
                var row = mapper.findBudgetSubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                // P1: risk inputs and classification are precomputed deterministically here and
                // frozen into the snapshot; the model only explains them.
                var evidence = intelligence.assessScopeBudget(organizationId, row.scopeType(),
                        row.scopeId(), row.actualAmount(), row.committedAmount(), row.totalAmount(),
                        row.currency(), row.id());
                var facts = List.of(
                        new AdvisorEvidence.MoneyFact("budget:" + row.id() + ":actual", "actual",
                                row.actualAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("budget:" + row.id() + ":committed", "committed",
                                row.committedAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("budget:" + row.id() + ":total", "total",
                                row.totalAmount(), row.currency()));
                final String riskBlock;
                try {
                    riskBlock = objectMapper.writeValueAsString(java.util.Map.of(
                            "scope", row.scopeType() + ":" + row.scopeId(),
                            "actual", evidence.actual().toPlainString(),
                            "committed", evidence.committed().toPlainString(),
                            "reservations", evidence.reservations().toPlainString(),
                            "forecastFuture", evidence.forecastFutureUsage().toPlainString(),
                            "immediate", evidence.immediateExposure().toPlainString(),
                            "projected", evidence.projectedPeriodEnd().toPlainString(),
                            "total", evidence.budgetTotal().toPlainString(),
                            "risk", evidence.risk()));
                } catch (Exception ex) {
                    throw new IllegalStateException("Advisor risk evidence is unavailable", ex);
                }
                yield AdvisorEvidence.build("BUDGET_RISK", row.id(), row.currency(), facts, List.of(),
                        "", riskBlock, "", clock.instant());
            }
            default -> throw validationFailed("Subject type must be ANOMALY, FORECAST, BUDGET_RISK or SAVINGS.");
        };
    }

    /** Deterministic driver reference ids, aligned with the snapshot driver order. */
    static List<String> driverReferenceIds(String subjectType, long subjectId, int driverCount) {
        var refs = new java.util.ArrayList<String>();
        for (var i = 0; i < driverCount; i++) {
            refs.add(subjectType.toLowerCase(java.util.Locale.ROOT) + ":" + subjectId + ":driver:" + i);
        }
        return refs;
    }

    private List<String> driverReferenceIds(AdvisorEvidence.Envelope envelope) {
        return driverReferenceIds(envelope.subjectType(), envelope.subjectId(),
                envelope.drivers().size());
    }

    /**
     * Snapshot facts with amounts as plain decimal strings (never JSON numbers): JSON number
     * spellings do not preserve BigDecimal scale ({@code 12.50} may read back as {@code 12.5}),
     * which would break fingerprint recomputation on the Gateway side.
     */
    private String snapshotFactsJson(AdvisorEvidence.Envelope envelope) {
        try {
            var out = new java.util.ArrayList<java.util.Map<String, String>>();
            for (var fact : envelope.facts()) {
                out.add(java.util.Map.of("factId", fact.factId(), "label", fact.label(),
                        "amount", fact.amount().toPlainString(), "currency", fact.currency()));
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence snapshot is unavailable", ex);
        }
    }

    private String snapshotDriversJson(AdvisorEvidence.Envelope envelope) {
        try {
            var refs = driverReferenceIds(envelope);
            var out = new java.util.ArrayList<java.util.Map<String, String>>();
            for (var i = 0; i < envelope.drivers().size(); i++) {
                var driver = envelope.drivers().get(i);
                out.add(java.util.Map.of("id", refs.get(i), "dimension", driver.dimension(),
                        "key", driver.key(), "deltaAmount", driver.deltaAmount().toPlainString(),
                        "currency", driver.currency()));
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence snapshot is unavailable", ex);
        }
    }

    private java.util.Map<String, String> snapshotSummary(AdvisorEvidence.Envelope envelope) {
        return java.util.Map.of("forecastSummary", envelope.forecastSummary(),
                "budgetRiskSummary", envelope.budgetRiskSummary(),
                "savingsSummary", envelope.savingsSummary());
    }

    /**
     * Parses the persisted deterministic contribution analysis ({@code [{dimension,key,delta}]})
     * into bounded drivers. Corrupt or absent driver data yields an explicitly empty list so the
     * model never invents drivers; money stays BigDecimal.
     */
    private List<AdvisorEvidence.Driver> parseAnomalyDrivers(AdvisorMapper.AnomalySubject row) {
        var drivers = new java.util.ArrayList<AdvisorEvidence.Driver>();
        try {
            var raw = row.driversJson();
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            var node = objectMapper.readTree(raw);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            for (var item : node) {
                if (drivers.size() >= AdvisorEvidence.MAX_DRIVERS) {
                    break;
                }
                var dimension = item.path("dimension").asText("").strip();
                var key = item.path("key").asText("").strip();
                var deltaText = item.path("delta").asText("").strip();
                if (dimension.isEmpty() || key.isEmpty() || deltaText.isEmpty()) {
                    continue;
                }
                drivers.add(new AdvisorEvidence.Driver(dimension, key,
                        new java.math.BigDecimal(deltaText), row.currency()));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(drivers);
    }

    public record ProfileResponse(
            long id, int version, long providerModelId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode,
            String status) {
    }

    public record JobResponse(
            long id, String subjectType, long subjectId, String status, int attemptCount,
            Long gatewayRequestId, String failureCode, List<AttemptResponse> attempts,
            ExplanationResponse explanation) {
    }

    public record AttemptResponse(int attemptNo, Long gatewayRequestId, String status, String failureCode) {
    }

    public record ExplanationResponse(String summary, String driversExplanation, int attemptNo) {
    }

    public record ClaimedJob(long jobId, long organizationId, String token, int attemptNo) {
    }

    /** Fact references bound to one job attempt (for output validation). */
    public Set<String> knownFactReferences(long jobId, long organizationId) {
        var refsJson = mapper.findEvidenceRefs(jobId, organizationId);
        if (refsJson == null || refsJson.isBlank()) {
            return Set.of();
        }
        try {
            return objectMapper.readValue(refsJson,
                    objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence references are unreadable", ex);
        }
    }
}
