package com.aicostops.advisor.application;

import com.aicostops.audit.application.AuditService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static com.aicostops.advisor.application.AdvisorErrors.*;
import com.aicostops.advisor.infrastructure.AdvisorJobMapper;

/** 短事务内的任务领取、网关联接与终结。此服务不执行 Provider I/O。 */
@Service
public class AdvisorJobLifecycleService {
    private final AdvisorJobMapper mapper;
    private final AdvisorJobQueryService queries;
    private final AdvisorOutputValidator outputValidator;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    private final SecureRandom random = new SecureRandom();
    public AdvisorJobLifecycleService(
            AdvisorJobMapper mapper,
            AdvisorJobQueryService queries,
            AdvisorOutputValidator outputValidator,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mapper = mapper;
        this.queries = queries;
        this.outputValidator = outputValidator;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AdvisorJobClaim claimNext(String workerId) {
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
        return new AdvisorJobClaim(candidate.id(), candidate.orgId(), token, candidate.attemptCount());
    }

    @Transactional
    public void linkGateway(long jobId, long organizationId, String token, long gatewayRequestId) {
        if (mapper.linkGateway(jobId, organizationId, token, "DISPATCHING", gatewayRequestId,
                clock.instant().plus(CLAIM_LEASE)) != 1) {
            throw stateConflict("Advisor job claim is not held by this worker.");
        }
        var job = queries.requireJob(organizationId, jobId);
        if (mapper.linkAttempt(organizationId, jobId, job.attemptCount(), gatewayRequestId) != 1) {
            throw stateConflict("Advisor attempt link was not recorded.");
        }
    }

    @Transactional
    public void complete(long jobId, long organizationId, String token, String rawNarrativeJson,
            Set<String> knownFactReferences) {
        var job = queries.requireJob(organizationId, jobId);
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
        audit.append("AI_ADVISOR_EXPLANATION_COMPLETED", organizationId, job.requestedByUserId(),
                "ADVISOR_JOB", jobId, Map.of("attempt", job.attemptCount()));
    }

    @Transactional
    public void fail(long jobId, long organizationId, String token, String failureCode) {
        var job = queries.requireJob(organizationId, jobId);
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

    @Transactional
    public void terminalizeAttempt(long jobId, long organizationId, int attemptNo, String claimToken,
            String terminalStatus, String failureCode) {
        var job = queries.requireJob(organizationId, jobId);
        if (job.attemptCount() != attemptNo) {
            throw stateConflict("Only the current Advisor attempt can terminalize the job.");
        }
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

    @Transactional
    public int reclaimOrphans(long organizationId) {
        return mapper.reclaimOrphans(organizationId, clock.instant());
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
}
