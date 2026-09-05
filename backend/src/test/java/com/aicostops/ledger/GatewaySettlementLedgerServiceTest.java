package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.ledger.application.GatewaySettlementLedgerService;
import com.aicostops.ledger.application.GatewaySettlementLedgerPort.PostCommand;
import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.ledger.domain.LedgerEntryType;
import com.aicostops.ledger.domain.LedgerPosting;
import com.aicostops.ledger.domain.LedgerPostingActorType;
import com.aicostops.ledger.domain.LedgerSourceType;
import com.aicostops.ledger.infrastructure.LedgerPostingMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GatewaySettlementLedgerServiceTest {

    private final LedgerPostingMapper mapper = Mockito.mock(LedgerPostingMapper.class);
    private final GatewaySettlementLedgerService service = new GatewaySettlementLedgerService(mapper);

    @Test
    void postsSystemGatewaySettlementWithStableSourceKeyAndConvergesExistingPosting() {
        var command = command();
        when(mapper.selectPostingByKey(7, "GATEWAY_SETTLEMENT:9")).thenReturn(null);
        when(mapper.lastInsertId()).thenReturn(44L);

        assertThat(service.post(command)).isEqualTo(44L);

        verify(mapper).insertSystemGatewaySettlementPosting(7, "GATEWAY_SETTLEMENT:9", 9,
                3, command.now(), command.now());
        verify(mapper).insertGatewaySettlementEntry(7, 44, 0, "COST",
                command.amount(), "USD", 12L, null, null, 88L, 9L, command.now());
    }

    @Test
    void existingSystemPostingIsReturnedWithoutSecondInsert() {
        var command = command();
        var posting = new LedgerPosting(44, 7, "GATEWAY_SETTLEMENT:9",
                LedgerSourceType.GATEWAY_SETTLEMENT, 9, null, 3, "POSTED",
                LedgerPostingActorType.SYSTEM, null, command.now(), command.now());
        var entry = new LedgerEntry(55, 7, 44, 0, LedgerEntryType.COST,
                command.amount(), "USD", 12L, null, null, 88L, null, null, 9L,
                null, null, null, null, command.now());
        when(mapper.selectPostingByKey(7, "GATEWAY_SETTLEMENT:9")).thenReturn(posting);
        when(mapper.selectEntriesByPostingId(7, 44)).thenReturn(List.of(entry));

        assertThat(service.post(command)).isEqualTo(44L);
        verify(mapper, never()).insertSystemGatewaySettlementPosting(any(Long.class), any(),
                any(Long.class), any(Long.class), any(), any());
    }

    @Test
    void rejectsInvalidTargetInsteadOfCreatingAClientControlledFinancialTarget() {
        assertThatThrownBy(() -> service.post(new PostCommand(7, 9, 3,
                new BigDecimal("1.00000000"), "USD", "PROJECT", 0, 88L,
                Instant.parse("2026-09-04T00:00:00Z")))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private static PostCommand command() {
        return new PostCommand(7, 9, 3, new BigDecimal("1.80000000"), "USD",
                "PROJECT", 12, 88L, Instant.parse("2026-09-04T00:00:00Z"));
    }
}
