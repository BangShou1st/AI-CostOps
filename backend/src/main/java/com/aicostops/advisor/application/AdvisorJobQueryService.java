package com.aicostops.advisor.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import static com.aicostops.advisor.application.AdvisorErrors.*;
import com.aicostops.advisor.infrastructure.AdvisorJobMapper;
import com.aicostops.advisor.infrastructure.AdvisorRows.JobRow;
import com.aicostops.advisor.application.AdvisorReadModels.JobResponse;
import com.aicostops.advisor.application.AdvisorReadModels.AttemptResponse;
import com.aicostops.advisor.application.AdvisorReadModels.ExplanationResponse;

/** 任务读取与响应装配；内部读取必须使用调用方已确认的组织 ID。 */
@Service
public class AdvisorJobQueryService {
    private final AuthorizationContextService authorizationContexts;
    private final AdvisorJobMapper mapper;
    private final ObjectMapper objectMapper;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public AdvisorJobQueryService(
            AuthorizationContextService authorizationContexts,
            AdvisorJobMapper mapper,
            ObjectMapper objectMapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public JobResponse explanation(AuthenticatedUser user, long jobId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        return jobResponse(requireJob(context.organizationId(), jobId));
    }

    JobRow requireJob(long organizationId, long jobId) {
        var job = mapper.findJob(jobId, organizationId);
        if (job == null) {
            throw notFound("Advisor explanation was not found.");
        }
        return job;
    }

    JobResponse jobResponse(JobRow job) {
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
