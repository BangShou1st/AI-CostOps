package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.AuthorizationContextCache;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class AuthorizationContextConfiguration {

    @Bean
    AuthorizationContextCache authorizationContextCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${aicostops.iam.context-cache-ttl:60s}") Duration ttl) {
        return new RedisAuthorizationContextCache(redis, objectMapper, ttl);
    }
}
