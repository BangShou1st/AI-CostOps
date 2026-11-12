package com.aicostops.gatewayadmin.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.shared.json.ApiId;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PricingRateResponseSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tinyUnitPriceIsSerializedAsPlainScale8String() throws Exception {
        var response = new ControlPlaneDtos.PricingRateResponse(
                ApiId.of(22), "INPUT_TOKEN", 1_000_000L, new BigDecimal("0.00000001"));

        var json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"unitPrice\":\"0.00000001\"");
        assertThat(json).doesNotContain("1E-8");
    }

    @Test
    void ordinaryUnitPriceKeepsScale8String() throws Exception {
        var response = new ControlPlaneDtos.PricingRateResponse(
                ApiId.of(1), "INPUT_TOKEN", 1_000_000L, new BigDecimal("30.0"));

        var json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"unitPrice\":\"30.00000000\"");
    }
}
