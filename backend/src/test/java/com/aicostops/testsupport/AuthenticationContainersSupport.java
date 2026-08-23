package com.aicostops.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class AuthenticationContainersSupport extends MySqlContainerSupport {

    private static final String REDIS_PASSWORD = "redis-auth-test-only";

    protected static final GenericContainer<?> AUTH_REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.8.1-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    static {
        AUTH_REDIS.start();
    }

    @DynamicPropertySource
    static void registerAuthenticationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", AUTH_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> AUTH_REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("spring.data.redis.timeout", () -> "5000ms");
    }
}
