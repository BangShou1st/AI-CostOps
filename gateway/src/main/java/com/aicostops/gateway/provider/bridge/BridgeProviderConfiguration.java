package com.aicostops.gateway.provider.bridge;

import com.aicostops.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bangshou1st.modelbridge.core.http.HttpTransport;
import io.github.bangshou1st.modelbridge.core.protocol.AdapterRegistry;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.openai.OpenAiCompatibleAdapter;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the one process-local ai-model-bridge client used by the Gateway.
 *
 * <p>Only the Spring-free core is used. The bridge server and starter are
 * deliberately excluded: adding either would introduce a third deployable or
 * an auto-configured Jackson 2 bean outside the Gateway's bounded lifecycle.
 */
@Configuration
public class BridgeProviderConfiguration {

    @Bean(name = "bridgeProviderExecutor", destroyMethod = "close")
    public ExecutorService bridgeProviderExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ModelBridgeClient modelBridgeClient(
            GatewayProperties properties,
            @Qualifier("bridgeProviderExecutor") ExecutorService bridgeProviderExecutor,
            @Value("${aicostops.gateway.provider.allow-private-endpoints:false}")
            boolean allowPrivateEndpoints) {
        var mapper = new ObjectMapper();
        var policy = allowPrivateEndpoints
                ? OutboundEndpointPolicy.privateNetwork(true,
                        Set.of("127.0.0.1", "::1", "localhost"), Set.of())
                : OutboundEndpointPolicy.httpsOnly();
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(bridgeProviderExecutor)
                .build();
        var transport = new HttpTransport(
                mapper,
                policy,
                Duration.ofMillis(properties.getConnectTimeoutMs()),
                Duration.ofMillis(properties.getHeaderTimeoutMs()),
                client);
        var registry = new AdapterRegistry()
                .register(new PermissiveOpenAiCompatibleAdapter(
                        new OpenAiCompatibleAdapter(mapper, transport)));
        return new ModelBridgeClient(registry);
    }
}
