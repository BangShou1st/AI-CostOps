package com.aicostops.evidence.application;

import com.aicostops.evidence.infrastructure.EvidenceMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Authorized Evidence review reads. Identifiers stay {@code long} in the
 * application/read-model layer; only the HTTP response boundary converts them
 * to decimal strings for the browser.
 */
@Service
public class EvidenceQueryService {

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final EvidenceMapper evidenceMapper;

    public EvidenceQueryService(
            AuthorizationContextService authorizationContexts,
            EvidenceMapper evidenceMapper) {
        this.authorizationContexts = authorizationContexts;
        this.evidenceMapper = evidenceMapper;
    }

    public PageResponse<EvidenceSummary> list(AuthenticatedUser user, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "EVIDENCE_READ");
        var pageRequest = validPage(page, size);
        var organizationId = context.organizationId();
        var rows = evidenceMapper.pageByOrganization(
                organizationId, (long) pageRequest.page() * pageRequest.size(), pageRequest.size());
        var total = evidenceMapper.countByOrganization(organizationId);
        var summaries = rows.stream().map(EvidenceSummary::from).toList();
        return PageResponse.of(summaries, pageRequest, total);
    }

    public EvidenceSummary get(AuthenticatedUser user, long evidenceId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "EVIDENCE_READ");
        var evidence = evidenceMapper.findByIdAndOrganization(evidenceId, context.organizationId());
        if (evidence == null) {
            throw notFound();
        }
        return EvidenceSummary.from(evidence);
    }

    private static PageRequest validPage(int page, int size) {
        try {
            return PageRequest.of(page, size);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid page or size",
                    "Page must be zero or greater and size must be between 1 and 200.");
        }
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Evidence not found", "The evidence is not available in the current organization.");
    }

    public record EvidenceSummary(
            long id,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            String sha256,
            String storageStatus,
            String storageErrorCode,
            long uploadedByMemberId,
            Instant createdAt,
            Instant updatedAt) {

        static EvidenceSummary from(com.aicostops.evidence.domain.Evidence evidence) {
            return new EvidenceSummary(
                    evidence.id(),
                    evidence.originalFilename(),
                    evidence.mediaType(),
                    evidence.sizeBytes(),
                    evidence.sha256(),
                    evidence.storageStatus().name(),
                    evidence.storageErrorCode(),
                    evidence.uploadedByMemberId(),
                    evidence.createdAt(),
                    evidence.updatedAt());
        }
    }
}
