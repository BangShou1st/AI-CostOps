package com.aicostops.gatewayadmin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aicostops.gatewayadmin.api.ControlPlaneDtos;
import com.aicostops.gatewayadmin.infrastructure.ControlPlaneMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * P2-13: pricing creation rejects duplicate dimensions before writing and maps
 * a concurrent version insert (unique-key race) to a 409 state conflict.
 */
@ExtendWith(MockitoExtension.class)
class ModelPricingServiceTest {

    private static final long ORG_ID = 11L;
    private static final long USER_ID = 22L;
    private static final long ORG_MEMBER_ID = 33L;
    private static final long PROVIDER_ACCOUNT_ID = 44L;
    private static final long PROVIDER_MODEL_ID = 55L;
    private static final long MODEL_ID = 66L;
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-09-08T00:00:00Z");

    @Mock
    private AuthorizationContextService authorizationContexts;
    @Mock
    private ControlPlaneMapper mapper;
    @Mock
    private GatewayAdminAuditPort audit;

    @Test
    void duplicateDimensionIsRejectedBeforeAnyPricingWrite() {
        stubScope();

        var thrown = catchThrowableOfType(DomainException.class, () -> service()
                .createPricingVersion(user(), request(List.of(
                        rate("INPUT_TOKEN", "1.0"),
                        rate("input_token", "2.0")))));

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
        assertThat(thrown.getMessage()).contains("INPUT_TOKEN");
        verify(mapper, never()).insertPricingVersion(anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), any(), any());
        verify(mapper, never()).insertPricingRate(anyLong(), anyLong(), anyString(), anyLong(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void concurrentVersionInsertIsReportedAsStateConflict() {
        stubScope();
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORG_ID);
        when(mapper.selectMaxPricingVersion(ORG_ID, PROVIDER_ACCOUNT_ID, PROVIDER_MODEL_ID)).thenReturn(0);
        when(mapper.insertPricingVersion(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uq_pricing_version_identity"));

        var thrown = catchThrowableOfType(DomainException.class, () -> service()
                .createPricingVersion(user(), request(List.of(
                        rate("INPUT_TOKEN", "1.0"),
                        rate("OUTPUT_TOKEN", "2.0")))));

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(thrown.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
        verify(mapper, never()).insertPricingRate(anyLong(), anyLong(), anyString(), anyLong(), any());
        verifyNoInteractions(audit);
    }

    private ModelPricingService service() {
        return new ModelPricingService(authorizationContexts, mapper, audit);
    }

    private void stubScope() {
        when(authorizationContexts.current(user())).thenReturn(new AuthorizationContext(
                USER_ID, ORG_ID, ORG_MEMBER_ID, 7,
                Set.of(new ScopedPermissionGrant("PROVIDER_ACCOUNT_MANAGE", ScopeType.ORG, ORG_ID)),
                Set.of("CP_MANAGER")));
        when(mapper.selectProviderAccount(ORG_ID, PROVIDER_ACCOUNT_ID)).thenReturn(
                new ControlPlaneMapper.ProviderAccountRow(
                        PROVIDER_ACCOUNT_ID, "TESTPC", "Test Account", "ACTIVE"));
        when(mapper.selectProviderModel(ORG_ID, PROVIDER_ACCOUNT_ID, PROVIDER_MODEL_ID)).thenReturn(
                new ControlPlaneMapper.ProviderModelRow(
                        PROVIDER_MODEL_ID, "TESTPC", MODEL_ID, "Test Model", "ACTIVE", true));
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(USER_ID, 7);
    }

    private static ControlPlaneDtos.PricingVersionCreateRequest request(
            List<ControlPlaneDtos.PricingRateInput> rates) {
        return new ControlPlaneDtos.PricingVersionCreateRequest(
                PROVIDER_ACCOUNT_ID, PROVIDER_MODEL_ID, "USD", EFFECTIVE_FROM, null, rates);
    }

    private static ControlPlaneDtos.PricingRateInput rate(String dimensionCode, String unitPrice) {
        return new ControlPlaneDtos.PricingRateInput(dimensionCode, 1_000_000L, new BigDecimal(unitPrice));
    }
}
