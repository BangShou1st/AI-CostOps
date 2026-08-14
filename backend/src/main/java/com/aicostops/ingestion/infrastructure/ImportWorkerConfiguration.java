package com.aicostops.ingestion.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportWorkerProperties.class)
public class ImportWorkerConfiguration {
}
