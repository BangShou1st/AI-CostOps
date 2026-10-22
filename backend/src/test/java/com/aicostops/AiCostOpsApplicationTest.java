package com.aicostops;

import com.aicostops.intelligence.application.CostIntelligenceScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class AiCostOpsApplicationTest {

    @MockitoBean
    CostIntelligenceScheduler backgroundScheduler;

    @Test
    void contextLoads() {
    }
}
