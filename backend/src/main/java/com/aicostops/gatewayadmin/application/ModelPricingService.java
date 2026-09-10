package com.aicostops.gatewayadmin.application;

import com.aicostops.gatewayadmin.api.ControlPlaneDtos;
import com.aicostops.gatewayadmin.infrastructure.ControlPlaneMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal governed Model/Pricing setup surface required by Browser UAT-01:
 * active model catalog and provider-model listing, plus org-scoped pricing
 * version creation and activation. Monetary rates stay decimal-string /
 * {@code BigDecimal} compatible (DECIMAL column on the wire as JSON numbers for
 * requests, engineering-grade rate objects internally); pricing version
 * lineage is never broken (append-only versions, activation retires prior
 * ACTIVE versions of the same scope).
 */
@Service
public class ModelPricingService {

    private static final Set<String> DIMENSIONS =
            Set.of("INPUT_TOKEN", "OUTPUT_TOKEN", "CACHED_INPUT_TOKEN", "REQUEST");

    private final AuthorizationContextService authorizationContexts;
    private final ControlPlaneMapper mapper;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final GatewayAdminAuditPort audit;

    public ModelPricingService(AuthorizationContextService authorizationContexts,
            ControlPlaneMapper mapper, GatewayAdminAuditPort audit) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.audit = audit;
    }

    public List<ControlPlaneDtos.ModelCatalogResponse> listModels(AuthenticatedUser user) {
        authorization.requireOrg(authorizationContexts.current(user), "PROVIDER_ACCOUNT_READ");
        return mapper.selectActiveModels().stream()
                .map(row -> new ControlPlaneDtos.ModelCatalogResponse(
                        ApiId.of(row.id()), row.modelKey(), row.name(), row.status()))
                .toList();
    }

    public List<ControlPlaneDtos.ProviderModelResponse> listProviderModels(AuthenticatedUser user) {
        authorization.requireOrg(authorizationContexts.current(user), "PROVIDER_ACCOUNT_READ");
        return mapper.selectActiveProviderModels().stream()
                .map(row -> new ControlPlaneDtos.ProviderModelResponse(
                        ApiId.of(row.id()), row.providerCode(), ApiId.of(row.modelId()),
                        row.providerModelName(), row.status(), row.routingEligible()))
                .toList();
    }

    public List<ControlPlaneDtos.PricingVersionResponse> listPricingVersions(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return mapper.selectPricingVersions(context.organizationId()).stream()
                .map(row -> toResponse(context.organizationId(), row)).toList();
    }

    @Transactional
    public ControlPlaneDtos.PricingVersionResponse createPricingVersion(AuthenticatedUser user,
            ControlPlaneDtos.PricingVersionCreateRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        long orgId = context.organizationId();

        var account = mapper.selectProviderAccount(orgId, request.providerAccountId());
        if (account == null || !account.status().equals("ACTIVE")) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Provider account not found", "The provider account is not active in this organization.");
        }
        var providerModel = mapper.selectProviderModel(request.providerModelId());
        if (providerModel == null || !providerModel.status().equals("ACTIVE")
                || !providerModel.providerCode().equals(account.providerCode())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Provider model is not compatible",
                    "The provider model must be ACTIVE and share the provider code of the provider account.");
        }
        if (request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Pricing interval is invalid", "effectiveTo must be after effectiveFrom.");
        }
        if (!DIMENSIONS.containsAll(request.rates().stream().map(r -> r.dimensionCode().toUpperCase(Locale.ROOT)).toList())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Pricing dimension is invalid",
                    "Supported dimensions: INPUT_TOKEN, OUTPUT_TOKEN, CACHED_INPUT_TOKEN, REQUEST.");
        }

        int version = mapper.selectMaxPricingVersion(orgId, request.providerAccountId(), request.providerModelId()) + 1;
        mapper.insertPricingVersion(orgId, request.providerAccountId(), request.providerModelId(),
                version, request.currency().toUpperCase(Locale.ROOT), request.effectiveFrom(), request.effectiveTo());
        long pricingVersionId = mapper.lastInsertId();
        for (ControlPlaneDtos.PricingRateInput rate : request.rates()) {
            mapper.insertPricingRate(orgId, pricingVersionId,
                    rate.dimensionCode().toUpperCase(Locale.ROOT), rate.unitQuantity(), rate.unitPrice());
        }
        audit.pricingVersionCreated(orgId, user.userId(), pricingVersionId,
                request.providerAccountId(), request.providerModelId(), version,
                request.currency().toUpperCase(Locale.ROOT), "DRAFT");
        return get(orgId, pricingVersionId);
    }

    @Transactional
    public ControlPlaneDtos.PricingVersionResponse activate(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        long orgId = context.organizationId();
        var row = requirePricingVersion(orgId, id);
        if (!row.status().equals("DRAFT")) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Pricing version is not a draft",
                    "Only a DRAFT pricing version can be activated; this one is " + row.status() + ".");
        }
        mapper.activatePricingVersion(orgId, id);
        mapper.retireOtherActivePricingVersions(orgId, row.providerAccountId(), row.providerModelId(), id);
        audit.pricingVersionActivated(orgId, user.userId(), id,
                row.providerAccountId(), row.providerModelId(), row.version());
        return get(orgId, id);
    }

    private ControlPlaneDtos.PricingVersionResponse get(long orgId, long id) {
        return toResponse(orgId, requirePricingVersion(orgId, id));
    }

    private ControlPlaneMapper.PricingVersionRow requirePricingVersion(long orgId, long id) {
        var row = mapper.selectPricingVersion(orgId, id);
        if (row == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Resource not found", "The resource is not available at the granted scope.");
        }
        return row;
    }

    private ControlPlaneDtos.PricingVersionResponse toResponse(long orgId,
            ControlPlaneMapper.PricingVersionRow row) {
        var rates = mapper.selectPricingRates(orgId, row.id()).stream()
                .map(rate -> new ControlPlaneDtos.PricingRateResponse(
                        ApiId.of(rate.id()), rate.dimensionCode(), rate.unitQuantity(), rate.unitPrice()))
                .toList();
        return new ControlPlaneDtos.PricingVersionResponse(
                ApiId.of(row.id()), ApiId.of(row.providerAccountId()), ApiId.of(row.providerModelId()),
                row.version(), row.currency(), row.status(), row.effectiveFrom(), row.effectiveTo(),
                row.createdAt(), row.activatedAt(), rates);
    }
}