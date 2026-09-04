package com.aicostops.reconciliation.application.blockers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import com.aicostops.reconciliation.infrastructure.GatewayCloseBlockerMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** M12 Gateway financial-work blocker behavior on a mocked read projection. */
@ExtendWith(MockitoExtension.class)
class GatewayFinancialWorkBlockerProviderTest {

    private static final long ORG_ID = 11L;
    private static final long PERIOD_ID = 22L;

    @Mock
    private GatewayCloseBlockerMapper mapper;

    @Test
    void noUnresolvedGatewayWorkPasses() {
        when(mapper.countUnresolvedFinancialWork(ORG_ID, PERIOD_ID)).thenReturn(0L);
        when(mapper.countUnresolvedReservations(ORG_ID, PERIOD_ID)).thenReturn(0L);

        var result = new GatewayFinancialWorkBlockerProvider(mapper)
                .evaluate(new CloseBlockerContext(ORG_ID, PERIOD_ID,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z")));

        assertThat(result.passed()).isTrue();
        assertThat(result.code()).isEqualTo(CloseBlockerCode.PENDING_GATEWAY_FINANCIAL_WORK);
    }

    @Test
    void unresolvedPossibleBillableWorkFailsClose() {
        when(mapper.countUnresolvedFinancialWork(ORG_ID, PERIOD_ID)).thenReturn(3L);
        when(mapper.countUnresolvedReservations(ORG_ID, PERIOD_ID)).thenReturn(0L);

        var result = new GatewayFinancialWorkBlockerProvider(mapper)
                .evaluate(new CloseBlockerContext(ORG_ID, PERIOD_ID,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z")));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(3L);
        assertThat(result.summary()).containsKey("blockedStates");
    }

    @Test
    void unresolvedReservationsFailCloseWithoutUnresolvedRequests() {
        when(mapper.countUnresolvedFinancialWork(ORG_ID, PERIOD_ID)).thenReturn(0L);
        when(mapper.countUnresolvedReservations(ORG_ID, PERIOD_ID)).thenReturn(2L);

        var result = new GatewayFinancialWorkBlockerProvider(mapper)
                .evaluate(new CloseBlockerContext(ORG_ID, PERIOD_ID,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z")));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(2L);
        assertThat(result.summary()).containsKey("blockedReservationStates");
    }
}