package com.aicostops.advisor.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

import static com.aicostops.advisor.application.AdvisorErrors.*;
import com.aicostops.advisor.infrastructure.AdvisorProfileMapper;

/** 按配置版本轮换内部凭据。由配置服务持有事务，不单独提交。 */
@Service
public class AdvisorCredentialRotator {
    private final AdvisorProfileMapper mapper;
    private final SecureRandom random = new SecureRandom();
    public AdvisorCredentialRotator(AdvisorProfileMapper mapper) {
        this.mapper = mapper;
    }

    void rotate(long organizationId, long profileId, long projectId,
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

    private String internalPrefix() {
        // gateway_credential.credential_prefix is CHAR(12): keep the generated prefix exactly 12 chars.
        return "aic_" + HexFormat.of().formatHex(randomBytes(4));
    }

    private byte[] randomBytes(int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
