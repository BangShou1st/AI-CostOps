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
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public AdvisorService(
            AuthorizationContextService authorizationContexts,
            AdvisorMapper mapper,
            AdvisorOutputValidator outputValidator,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.outputValidator = outputValidator;
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
        if (!"ACTIVE".equals(mapper.findProviderModelStatus(request.providerModelId()))) {
            throw validationFailed("Advisor provider model must be ACTIVE.");
        }
        if (!"ACTIVE".equals(mapper.findProjectStatus(request.projectId(), context.organizationId()))) {
            throw validationFailed("Advisor project must be ACTIVE in this organization.");
        }
        var scope = request.financialScopeType() == null ? "" : request.financialScopeType();
        if (!scope.equals("PROJECT") && !scope.equals("TEAM") && !scope.equals("COST_CENTER")) {
            throw validationFailed("Financial scope type must be PROJECT, TEAM or COST_CENTER.");
        }
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
        ensureInternalIdentity(context.organizationId(), request.projectId(), budgetMode,
                request.providerModelId(), now);
        audit.append("AI_ADVISOR_PROFILE_UPDATED", context.organizationId(), user.userId(),
                "ADVISOR_PROFILE", created.id(), Map.of("version", created.version()));
        return profileResponse(created);
    }

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
        var envelope = AdvisorEvidence.build(request.subjectType(), request.subjectId(),
                request.currency(), request.facts(), request.drivers(), request.forecastSummary(),
                request.budgetRiskSummary(), request.savingsSummary(), clock.instant());
        var fingerprint = AdvisorEvidence.fingerprint(envelope);
        var now = clock.instant();
        final String refsJson;
        try {
            refsJson = objectMapper.writeValueAsString(envelope.factReferenceIds());
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence references are unavailable", ex);
        }
        mapper.insertJob(context.organizationId(), context.organizationMemberId(),
                envelope.subjectType(), envelope.subjectId(), fingerprint, refsJson, profile.version(), now);
        var jobId = mapper.lastInsertId();
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
        var token = HexFormat.of().formatHex(randomBytes(20)) + workerIdSuffix(workerId);
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
        mapper.linkAttempt(organizationId, jobId, job.attemptCount(), gatewayRequestId);
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
        audit.append("AI_ADVISOR_EXPLANATION_COMPLETED", organizationId, job.requestedBy(),
                "ADVISOR_JOB", jobId, Map.of("attempt", job.attemptCount()));
    }

    @Transactional
    public void fail(long jobId, long organizationId, String token, String failureCode) {
        if (mapper.markFailed(jobId, organizationId, token, failureCode, clock.instant()) != 1) {
            throw stateConflict("Advisor job claim is not held by this worker.");
        }
        audit.append("AI_ADVISOR_EXPLANATION_FAILED", organizationId, null,
                "ADVISOR_JOB", jobId, Map.of("failureCode", failureCode));
    }

    /** Crash recovery Case A: reclaim lease-expired jobs with no Gateway link. */
    @Transactional
    public int reclaimOrphans(long organizationId) {
        return mapper.reclaimOrphans(organizationId, clock.instant());
    }

    private void ensureInternalIdentity(long organizationId, long projectId, String budgetMode,
            long providerModelId, Instant now) {
        mapper.ensureAdvisorIdentity(organizationId, now);
        var identityId = mapper.findAdvisorIdentity(organizationId);
        if (identityId == null) {
            throw new IllegalStateException("Advisor service identity is unavailable");
        }
        if (mapper.findInternalCredential(organizationId, identityId) == null) {
            mapper.insertInternalCredential(organizationId, internalPrefix(), randomBytes(32),
                    identityId, projectId, budgetMode, now);
        }
        var credentialId = mapper.findInternalCredential(organizationId, identityId);
        var logicalModelId = mapper.findLogicalModelOf(providerModelId);
        if (credentialId != null && logicalModelId != null) {
            mapper.allowCredentialModel(credentialId, organizationId, logicalModelId, now);
        }
    }

    private String internalPrefix() {
        return "aic_int_" + HexFormat.of().formatHex(randomBytes(4));
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

    public record ExplanationRequest(
            String subjectType,
            long subjectId,
            String currency,
            List<AdvisorEvidence.MoneyFact> facts,
            List<AdvisorEvidence.Driver> drivers,
            String forecastSummary,
            String budgetRiskSummary,
            String savingsSummary) {
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
