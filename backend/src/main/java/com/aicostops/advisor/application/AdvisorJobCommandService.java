package com.aicostops.advisor.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.aicostops.advisor.application.AdvisorErrors.*;
import com.aicostops.advisor.infrastructure.AdvisorProfileMapper;
import com.aicostops.advisor.infrastructure.AdvisorJobMapper;
import com.aicostops.advisor.infrastructure.AdvisorEvidenceMapper;
import com.aicostops.advisor.application.AdvisorReadModels.JobResponse;
import com.aicostops.advisor.application.AdvisorRequests.ExplanationRequest;

/** 解释请求与显式重试；任务、冻结快照、尝试记录和审计一起提交。 */
@Service
public class AdvisorJobCommandService {
    private final AuthorizationContextService authorizationContexts;
    private final AdvisorJobMapper mapper;
    private final AdvisorProfileMapper profiles;
    private final AdvisorEvidenceMapper evidence;
    private final AdvisorJobQueryService queries;
    private final AdvisorEvidenceAssembler evidenceAssembler;
    private final AdvisorEvidenceSnapshotSerializer snapshots;
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private static final Set<String> SUBJECT_TYPES = Set.of("ANOMALY", "FORECAST", "BUDGET_RISK", "SAVINGS");

    public AdvisorJobCommandService(
            AuthorizationContextService authorizationContexts,
            AdvisorJobMapper mapper,
            AdvisorProfileMapper profiles,
            AdvisorEvidenceMapper evidence,
            AdvisorJobQueryService queries,
            AdvisorEvidenceAssembler evidenceAssembler,
            AdvisorEvidenceSnapshotSerializer snapshots,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.profiles = profiles;
        this.evidence = evidence;
        this.queries = queries;
        this.evidenceAssembler = evidenceAssembler;
        this.snapshots = snapshots;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public JobResponse requestExplanation(AuthenticatedUser user, ExplanationRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        var profile = profiles.findActiveProfile(context.organizationId());
        if (profile == null) {
            throw validationFailed("Advisor profile is not configured.");
        }
        if (request.subjectType() == null || !SUBJECT_TYPES.contains(request.subjectType())) {
            throw validationFailed("Subject type must be ANOMALY, FORECAST, BUDGET_RISK or SAVINGS.");
        }
        var envelope = evidenceAssembler.build(context.organizationId(), request.subjectType(), request.subjectId());
        var fingerprint = AdvisorEvidence.fingerprint(envelope);
        var now = clock.instant();
        var refsJson = snapshots.referencesJson(envelope);
        // P0: the job is bound to its exact profile revision (id + version); the worker must
        // never substitute the later ACTIVE revision.
        // requester identity: advisor_inference_job.requested_by is app_user.id (human
        // requester), never organization_member.id, because completion audits it as
        // audit_event.actor_user_id which FKs app_user(id).
        mapper.insertJob(context.organizationId(), user.userId(),
                envelope.subjectType(), envelope.subjectId(), fingerprint, refsJson, profile.version(),
                profile.id(), now);
        var jobId = mapper.lastInsertId();
        // P1: persist the immutable bounded evidence snapshot the model will actually receive.
        // The worker verifies the fingerprint before any Provider I/O instead of re-reading
        // mutable subject rows.
        try {
            evidence.insertEvidenceSnapshot(context.organizationId(), jobId, envelope.schemaVersion(),
                    envelope.subjectType(), envelope.subjectId(), envelope.currency(),
                    snapshots.factsJson(envelope),
                    snapshots.driversJson(envelope),
                    snapshots.summaryJson(envelope), fingerprint,
                    envelope.generatedAt(), now);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence snapshot is unavailable", ex);
        }
        mapper.insertAttempt(context.organizationId(), jobId, 1, now);
        audit.append("AI_ADVISOR_EXPLANATION_REQUESTED", context.organizationId(), user.userId(),
                "ADVISOR_JOB", jobId,
                Map.of("subject", envelope.subjectType(), "attempt", 1));
        return queries.jobResponse(queries.requireJob(context.organizationId(), jobId));
    }

    @Transactional
    public JobResponse retry(AuthenticatedUser user, long jobId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        var job = queries.requireJob(context.organizationId(), jobId);
        if (!"COMPLETED".equals(job.status()) && !"FAILED".equals(job.status())) {
            throw stateConflict("Only terminal advisor jobs can be retried.");
        }
        if (mapper.reopenForRetry(jobId, context.organizationId()) != 1) {
            throw new IllegalStateException("Advisor job could not be reopened");
        }
        var reopened = queries.requireJob(context.organizationId(), jobId);
        mapper.insertAttempt(context.organizationId(), jobId, reopened.attemptCount(), clock.instant());
        audit.append("AI_ADVISOR_EXPLANATION_REQUESTED", context.organizationId(), user.userId(),
                "ADVISOR_JOB", jobId, Map.of("retry", true, "attempt", reopened.attemptCount()));
        return queries.jobResponse(reopened);
    }
}
