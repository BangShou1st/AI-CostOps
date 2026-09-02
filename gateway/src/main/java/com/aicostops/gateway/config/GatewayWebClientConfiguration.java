package com.aicostops.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Supplies the base WebClient.Builder used by Provider adapters. */
@Configuration
public class GatewayWebClientConfiguration {

    @Bean
    public WebClient.Builder gatewayWebClientBuilder() {
        return WebClient.builder();
    }
}