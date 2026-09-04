package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gatewaysettlement.infrastructure.GatewayReservationSettlementMapper;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GatewayReservationSettlementMapperTest {

    @Test
    void exposesOnlyTheNarrowSettlementReservationAuthority() {
        var methodNames = Arrays.stream(GatewayReservationSettlementMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames).containsExactlyInAnyOrder(
                "selectById", "selectByIdForUpdate", "finalizeForSettlement");
        assertThat(methodNames).noneMatch(name -> name.contains("insert")
                || name.contains("release") || name.contains("resize")
                || name.contains("retarget") || name.contains("create"));
    }
}
