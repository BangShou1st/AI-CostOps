package com.aicostops.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.application.AllocationReadModels.AllocationDecisionView;
import com.aicostops.allocation.application.AllocationResponseCodec;
import com.aicostops.allocation.application.AllocationReadModels.AllocationRuleTrace;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationDecisionStatus;
import com.aicostops.attribution.domain.AllocationLine;
import com.aicostops.attribution.domain.AllocationSubjectType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AllocationResponseCodecTest {

    @Test
    void idempotentReplayPreservesAllocationLineId() {
        var now = Instant.parse("2026-08-19T00:00:00Z");
        var decision = new AllocationDecision(70L, 9L, AllocationSubjectType.CHARGE_FACT,
                31L, null, AllocationDecisionSource.MANUAL, null,
                AllocationDecisionStatus.CONFIRMED, 3L, now);
        var line = new AllocationLine(701L, 9L, 70L, 0,
                new BigDecimal("10.00000000"), "CNY", 77L, null, null, now);
        var view = new AllocationDecisionView(decision, List.of(line), (AllocationRuleTrace) null);
        var codec = new AllocationResponseCodec(new ObjectMapper());

        var replay = codec.decisionFromJson(codec.decisionToJson(view));

        assertThat(replay.lines()).singleElement().extracting(AllocationLine::id).isEqualTo(701L);
    }
}
