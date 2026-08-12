package com.aicostops.shared.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.shared.money.CurrencyCode;
import com.aicostops.shared.money.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApiJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesBigintApiIdsWithoutJavaScriptPrecisionLoss() {
        var json = objectMapper.writeValueAsString(new Resource(ApiId.of(9_007_199_254_740_993L)));

        assertThat(json).isEqualTo("{\"id\":\"9007199254740993\"}");
    }

    @Test
    void serializesMoneyAmountsAsDecimalStrings() {
        var money = Money.of(new BigDecimal("1.53512800"), CurrencyCode.of("CNY"));

        assertThat(objectMapper.writeValueAsString(money))
                .isEqualTo("{\"amount\":\"1.53512800\",\"currency\":\"CNY\"}");
    }

    private record Resource(ApiId id) {
    }
}
