package com.aicostops.evidence.application;

import com.aicostops.evidence.domain.Evidence;
import com.aicostops.evidence.infrastructure.EvidenceMapper;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactional Evidence reservation/finalization.
 *
 * <p>Storage code never talks to MyBatis directly; it only reserves, finalizes or
 * marks Evidence rows through this service. Duplicate-key races are converted into
 * idempotent lookup/reuse instead of surfacing as 500s.
 */
@Service
public class EvidencePersistenceService {

    private final EvidenceMapper mapper;

    public EvidencePersistenceService(EvidenceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public Evidence reserveOrReuse(
            long organizationId,
            String sha256,
            String objectKey,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            long uploadedByMemberId,
            Instant now) {
        var existing = mapper.findByOrganizationAndSha(organizationId, sha256);
        if (existing != null) {
            return existing;
        }
        try {
            mapper.insertStaging(organizationId, sha256, objectKey, originalFilename,
                    mediaType, sizeBytes, uploadedByMemberId, now);
        } catch (DuplicateKeyException concurrentDuplicate) {
            var winner = mapper.findByOrganizationAndSha(organizationId, sha256);
            if (winner != null) {
                return winner;
            }
            throw concurrentDuplicate;
        }
        var inserted = mapper.findByIdAndOrganization(mapper.lastInsertId(), organizationId);
        if (inserted == null) {
            throw new IllegalStateException("Reserved Evidence must be readable in its organization");
        }
        return inserted;
    }

    @Transactional
    public void markAvailable(long evidenceId, long organizationId, Instant now) {
        mapper.markAvailable(evidenceId, organizationId, now);
    }

    @Transactional
    public void markFailedUnlessAvailable(long evidenceId, long organizationId, String errorCode, Instant now) {
        mapper.markFailedUnlessAvailable(evidenceId, organizationId, errorCode, now);
    }

    public Optional<Evidence> findByIdAndOrganization(long evidenceId, long organizationId) {
        return Optional.ofNullable(mapper.findByIdAndOrganization(evidenceId, organizationId));
    }

    public Optional<Evidence> findByOrganizationAndSha(long organizationId, String sha256) {
        return Optional.ofNullable(mapper.findByOrganizationAndSha(organizationId, sha256));
    }
}
