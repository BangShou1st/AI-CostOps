package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.application.ProviderAdapter;
import com.aicostops.ingestion.application.ProviderAdapterRegistry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProviderAdapterRegistryConfiguration {

    @Bean
    ProviderAdapterRegistry providerAdapterRegistry(List<ProviderAdapter> adapters) {
        return new ProviderAdapterRegistry(adapters);
    }
}
