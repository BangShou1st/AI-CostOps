package com.aicostops.iam.infrastructure;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class AuthenticationRuntimeConfiguration {

    @Bean
    JwtTokenService jwtTokenService(
            @Value("${aicostops.auth.jwt-signing-secret}") String signingSecret,
            @Value("${aicostops.auth.access-token-lifetime:15m}") Duration lifetime,
            Clock clock) {
        return new JwtTokenService(signingSecret, lifetime, clock);
    }

    @Bean
    RedisRateLimiter redisRateLimiter(
            StringRedisTemplate redis,
            Clock clock,
            @Value("${aicostops.auth.login-ip-limit:20}") int ipLimit,
            @Value("${aicostops.auth.login-account-limit:8}") int accountLimit,
            @Value("${aicostops.auth.login-window:15m}") Duration window) {
        return new RedisRateLimiter(redis, clock, ipLimit, accountLimit, window);
    }

    @Bean
    RedisRefreshSessionRepository redisRefreshSessionRepository(
            StringRedisTemplate redis,
            Clock clock,
            @Value("${aicostops.auth.refresh-session-lifetime:7d}") Duration sessionLifetime,
            @Value("${aicostops.auth.refresh-race-window:10s}") Duration raceWindow) {
        return new RedisRefreshSessionRepository(redis, clock, new SecureRandom(), sessionLifetime, raceWindow);
    }
}
