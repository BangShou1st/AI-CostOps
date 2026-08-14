package com.aicostops.evidence.application;

import com.aicostops.evidence.domain.Evidence;
import com.aicostops.evidence.domain.EvidenceStorageStatus;
import com.aicostops.evidence.infrastructure.ObjectStorageException;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Authorized raw Evidence download: permission + ORG scope, org-scoped lookup,
 * and a strict AVAILABLE check before any object stream is opened.
 */
@Service
public class EvidenceDownloadService {

    private final AuthorizationContextService authorizationContexts;
    private final EvidencePersistenceService persistence;
    private final ObjectStoragePort storage;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public EvidenceDownloadService(
            AuthorizationContextService authorizationContexts,
            EvidencePersistenceService persistence,
            ObjectStoragePort storage) {
        this.authorizationContexts = authorizationContexts;
        this.persistence = persistence;
        this.storage = storage;
    }

    public EvidenceDownload download(AuthenticatedUser authenticatedUser, long evidenceId) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "EVIDENCE_DOWNLOAD");
        var evidence = persistence.findByIdAndOrganization(evidenceId, context.organizationId())
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                        "Evidence not found", "The evidence is not available in the current organization."));
        if (evidence.storageStatus() != EvidenceStorageStatus.AVAILABLE) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Evidence is not available", "The evidence has not finished storing.");
        }
        try {
            return new EvidenceDownload(evidence, storage.open(evidence.objectKey()));
        } catch (ObjectStorageException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE,
                    "Evidence storage unavailable", "The evidence object store is temporarily unavailable.");
        }
    }

    public record EvidenceDownload(Evidence evidence, InputStream stream) {
    }
}
