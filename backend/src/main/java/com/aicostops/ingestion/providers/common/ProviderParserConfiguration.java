package com.aicostops.ingestion.providers.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderParserProperties.class)
public class ProviderParserConfiguration {
}
