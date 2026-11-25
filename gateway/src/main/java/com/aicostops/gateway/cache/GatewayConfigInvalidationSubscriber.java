package com.aicostops.gateway.cache;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscribes to {@code aicostops:v2:cfg-invalidate} to evict local Caffeine L1
 * config entries (including local-only Provider credentials) on Control-Plane
 * commits. Clears ALL local caches on Pub/Sub disconnect/reconnect, and degrades
 * cleanly without crashing startup or tests when Redis is unavailable.
 */
@Component
public class GatewayConfigInvalidationSubscriber implements SmartLifecycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigInvalidationSubscriber.class);

    private final ReactiveRedisConnectionFactory connectionFactory;
    private final GatewayConfigCache configCache;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean subscribedOnce = new AtomicBoolean(false);
    private final AtomicBoolean pubSubSawDisconnect = new AtomicBoolean(false);

    private volatile ReactiveRedisMessageListenerContainer container;
    private volatile Disposable subscription;
    private volatile RedisConnectionStateListener connectionListener;

    public GatewayConfigInvalidationSubscriber(
            ObjectProvider<ReactiveRedisConnectionFactory> connectionFactoryProvider,
            GatewayConfigCache configCache,
            ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactoryProvider == null
                ? null : connectionFactoryProvider.getIfAvailable();
        this.configCache = configCache;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true) || connectionFactory == null) {
            return;
        }
        try {
            attachLettuceConnectionListener();
            this.container = new ReactiveRedisMessageListenerContainer(connectionFactory);
            var topic = ChannelTopic.of(GatewayConfigCacheKeys.INVALIDATION_CHANNEL);
            var listener = new SubscriptionListener() {
                @Override
                public void onChannelSubscribed(byte[] channel, long count) {
                    if (subscribedOnce.getAndSet(true)) {
                        log.info("Gateway config Pub/Sub re-subscribed; invalidating all local config caches");
                        configCache.invalidateAll();
                    }
                }
            };
            this.subscription = Flux.defer(() -> container.receive(List.of(topic), listener))
                    .doOnNext(message -> handleInvalidation(message.getMessage()))
                    .doOnError(ex -> {
                        if (subscribedOnce.get()) {
                            log.warn("Gateway config Pub/Sub disconnected; clearing all local config caches", ex);
                            configCache.invalidateAll();
                        } else {
                            log.debug("Gateway config Pub/Sub unavailable, retrying in background", ex);
                        }
                    })
                    .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(250))
                            .maxBackoff(Duration.ofSeconds(5)))
                    .subscribe(
                            ignored -> { },
                            ex -> log.debug("Gateway config Pub/Sub terminated", ex));
        } catch (RuntimeException ex) {
            log.warn("Gateway config Pub/Sub could not start; relying on local TTL fallback", ex);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        var currentSub = this.subscription;
        this.subscription = null;
        if (currentSub != null) {
            try {
                currentSub.dispose();
            } catch (RuntimeException ignored) {
                // Best-effort shutdown.
            }
        }
        var currentContainer = this.container;
        this.container = null;
        if (currentContainer != null) {
            try {
                currentContainer.destroy();
            } catch (RuntimeException ignored) {
                // Best-effort shutdown when Redis was never reachable.
            }
        }
        detachLettuceConnectionListener();
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public void handleInvalidation(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            var message = objectMapper.readValue(
                    payload, GatewayConfigCacheKeys.ConfigInvalidationMessage.class);
            if (message == null || message.type() == null) {
                return;
            }
            switch (message.type()) {
                case GatewayConfigCacheKeys.TYPE_CRED -> configCache.invalidateCredential(message.key());
                case GatewayConfigCacheKeys.TYPE_PRINCIPAL -> configCache.invalidatePrincipal(message.key());
                case GatewayConfigCacheKeys.TYPE_MODEL -> configCache.invalidateModelCatalog(message.key());
                case GatewayConfigCacheKeys.TYPE_MODEL_ALL -> configCache.invalidateAllModelCatalogs();
                case GatewayConfigCacheKeys.TYPE_ROUTE -> configCache.invalidateRoute(message.key());
                case GatewayConfigCacheKeys.TYPE_ROUTE_ORG -> configCache.invalidateOrgRoutes(message.key());
                case GatewayConfigCacheKeys.TYPE_ROUTE_ALL -> configCache.invalidateAllRoutes();
                case GatewayConfigCacheKeys.TYPE_PRICING -> configCache.invalidatePricing(message.key());
                case GatewayConfigCacheKeys.TYPE_CONN -> configCache.invalidateConnectionProfile(message.key());
                case GatewayConfigCacheKeys.TYPE_PROVIDER_CRED ->
                        configCache.invalidateProviderCredential(message.key());
                case GatewayConfigCacheKeys.TYPE_ALL -> configCache.invalidateAll();
                default -> log.debug("Ignoring unknown Gateway config invalidation type {}", message.type());
            }
        } catch (RuntimeException ex) {
            log.debug("Ignoring malformed Gateway config invalidation payload", ex);
        }
    }

    private void attachLettuceConnectionListener() {
        if (!(connectionFactory instanceof LettuceConnectionFactory lcf)) {
            return;
        }
        AbstractRedisClient client = lcf.getNativeClient();
        if (client == null) {
            return;
        }
        var listener = new RedisConnectionStateListener() {
            @Override
            public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
                if (connection instanceof StatefulRedisPubSubConnection<?, ?> && running.get()) {
                    pubSubSawDisconnect.set(true);
                    log.warn("Redis Pub/Sub connection disconnected; clearing all local config caches");
                    configCache.invalidateAll();
                }
            }

            @Override
            public void onRedisConnected(RedisChannelHandler<?, ?> connection, SocketAddress socketAddress) {
                if (connection instanceof StatefulRedisPubSubConnection<?, ?>
                        && running.get()
                        && pubSubSawDisconnect.compareAndSet(true, false)) {
                    log.info("Redis Pub/Sub connection reconnected; clearing all local config caches");
                    configCache.invalidateAll();
                }
            }
        };
        this.connectionListener = listener;
        client.addListener(listener);
    }

    private void detachLettuceConnectionListener() {
        var listener = this.connectionListener;
        this.connectionListener = null;
        if (listener != null && connectionFactory instanceof LettuceConnectionFactory lcf) {
            var client = lcf.getNativeClient();
            if (client != null) {
                client.removeListener(listener);
            }
        }
    }
}
