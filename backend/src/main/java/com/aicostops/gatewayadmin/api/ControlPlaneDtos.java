package com.aicostops.gatewayadmin.api;

import com.aicostops.shared.json.ApiId;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Wire DTOs of the governed Control Plane surface (Service Identity, Gateway
 * Credential, Model/Pricing). Request identity fields are JSON numbers to
 * match the backend numeric contract; response ids go through {@link ApiId}
 * (serialized as strings). Only {@link CredentialCreateResponse} ever carries
 * a raw Gateway key, and it is returned exactly once.
 */
public final class ControlPlaneDtos {

    private ControlPlaneDtos() {
    }

    public record ServiceIdentityRequest(
            @NotBlank @Size(max = 100) String code,
            @NotBlank @Size(max = 200) String name) {
    }

    public record ServiceIdentityResponse(
            ApiId id, String code, String name, String status, Instant createdAt) {
    }

    public record CredentialCreateRequest(
            @NotBlank String principalType,
            Long serviceIdentityId,
            Long organizationMemberId,
            @NotNull @Min(1) Long projectId,
            @NotBlank String financialScopeType,
            @NotNull @Min(1) Long financialScopeId,
            @NotBlank String budgetEnforcementMode,
            Instant expiresAt,
            @NotEmpty List<@NotNull @Min(1) Long> modelIds) {
    }

    public record CredentialCreateResponse(
            ApiId id,
            String prefix,
            String rawKey,
            String principalType,
            ApiId projectId,
            String financialScopeType,
            ApiId financialScopeId,
            String budgetEnforcementMode,
            String status,
            Instant expiresAt,
            Instant createdAt) {
    }

    public record CredentialResponse(
            ApiId id,
            String prefix,
            String principalType,
            ApiId serviceIdentityId,
            ApiId organizationMemberId,
            ApiId projectId,
            String financialScopeType,
            ApiId financialScopeId,
            String budgetEnforcementMode,
            String status,
            Instant expiresAt,
            Instant createdAt,
            Instant revokedAt) {
    }

    public record PricingRateInput(
            @NotBlank String dimensionCode,
            @NotNull @Min(1) Long unitQuantity,
            @NotNull @DecimalMin(value = "0", inclusive = true)
            @Digits(integer = 20, fraction = 8) BigDecimal unitPrice) {
    }

    public record PricingVersionCreateRequest(
            @NotNull @Min(1) Long providerAccountId,
            @NotNull @Min(1) Long providerModelId,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo,
            @NotEmpty List<@NotNull PricingRateInput> rates) {
    }

    public record PricingVersionResponse(
            ApiId id,
            ApiId providerAccountId,
            ApiId providerModelId,
            int version,
            String currency,
            String status,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant createdAt,
            Instant activatedAt,
            List<PricingRateResponse> rates) {
    }

    public record PricingRateResponse(
            ApiId id,
            String dimensionCode,
            long unitQuantity,
            BigDecimal unitPrice) {
    }

    public record ModelCatalogResponse(ApiId id, String modelKey, String name, String status) {
    }

    public record ProviderModelResponse(
            ApiId id, String providerCode, ApiId modelId, String providerModelName,
            String status, boolean routingEligible) {
    }
}