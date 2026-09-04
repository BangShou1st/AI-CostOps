package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.aicostops.audit.application.AuditService;
import com.aicostops.gatewaysettlement.infrastructure.AuditGatewaySettlementAdapter;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuditGatewaySettlementAdapterTest {

    @Test
    void writesSystemAuditWithSafeBoundedFinancialMetadata() {
        var audit = Mockito.mock(AuditService.class);
        var adapter = new AuditGatewaySettlementAdapter(audit);

        adapter.settlementPosted(1, 9, 11, 13, 15, 17, 19, 21,
                "PROJECT", 23, new BigDecimal("1.80000000"), "USD", 25L, true);

        @SuppressWarnings("unchecked")
        var metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).append(eq("GATEWAY_SETTLEMENT_POSTED"), eq(1L), eq(null),
                eq("GATEWAY_SETTLEMENT"), eq(9L), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("postedAmount", "1.80000000")
                .containsEntry("reservationOverrun", true)
                .doesNotContainKeys("prompt", "completion", "reasoning", "apiKey", "secret",
                        "providerBody");
    }
}
