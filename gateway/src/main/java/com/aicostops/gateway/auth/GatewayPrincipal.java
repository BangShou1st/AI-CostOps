package com.aicostops.gateway.auth;

/**
 * Authenticated commercial context attached to a Gateway request after key
 * authentication and principal/project/financial-scope validation. It never
 * carries the raw key, digests or Provider secrets.
 */
public record GatewayPrincipal(
        long credentialId,
        long organizationId,
        long projectId,
        String principalType,
        Long organizationMemberId,
        Long serviceIdentityId,
        String financialScopeType,
        long financialScopeId,
        String budgetEnforcementMode) {
}