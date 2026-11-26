package com.aicostops.gateway.testsupport;

import com.aicostops.gateway.cache.GatewayConfigCache;
import java.util.ArrayList;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Resets {@link GatewayConfigCache} (both local Caffeine L1 and Redis {@code cfg:*}
 * L2 entries) before and after Spring tests. Tests using the shared Redis
 * container also clear reservation-expiry and request-idempotency hints.
 */
public class GatewayCacheResetTestExecutionListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        resetCache(testContext);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        resetCache(testContext);
    }

    private static void resetCache(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        var cache = testContext.getApplicationContext()
                .getBeanProvider(GatewayConfigCache.class)
                .getIfAvailable();
        if (cache != null) {
            cache.resetForTests();
        }
        if (testContext.getTestInstance() instanceof GatewayMySqlContainerSupport) {
            var redis = testContext.getApplicationContext().getBean(StringRedisTemplate.class);
            var keys = new ArrayList<String>();
            keys.add("aicostops:v2:gateway:reservation-expiry");
            try (var cursor = redis.scan(ScanOptions.scanOptions()
                    .match("aicostops:v2:gateway:idem:*").count(500).build())) {
                cursor.forEachRemaining(keys::add);
            }
            redis.delete(keys);
        }
    }
}
