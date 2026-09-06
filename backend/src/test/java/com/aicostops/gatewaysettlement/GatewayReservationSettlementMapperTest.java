package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gatewaysettlement.infrastructure.GatewayReservationSettlementMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GatewayReservationSettlementMapperTest {

    @Test
    void exposesOnlyTheNarrowSettlementAndReconciliationReservationAuthority() {
        var methodNames = Arrays.stream(GatewayReservationSettlementMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        // M15 adds only the reviewed releaseForReconciliation transition; no
        // create/resize/retarget/insert semantics may ever appear here.
        assertThat(methodNames).containsExactlyInAnyOrder(
                "selectById", "selectByIdForUpdate", "finalizeForSettlement",
                "releaseForReconciliation");
        assertThat(methodNames).noneMatch(name -> name.contains("insert")
                || name.contains("resize") || name.contains("retarget")
                || name.contains("create"));
    }
}
