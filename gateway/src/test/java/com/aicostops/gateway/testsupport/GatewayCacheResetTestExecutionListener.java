package com.aicostops.gateway.testsupport;

import com.aicostops.gateway.cache.GatewayConfigCache;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Resets {@link GatewayConfigCache} (both local Caffeine L1 and Redis {@code cfg:*}
 * L2 entries) before and after every Spring test method so shared-container
 * integration tests reusing fixed key prefixes never leak cached state.
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
    }
}
