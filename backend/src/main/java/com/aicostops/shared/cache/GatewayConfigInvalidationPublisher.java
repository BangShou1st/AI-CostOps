package com.aicostops.shared.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Best-effort Control-Plane invalidation bridge for the Gateway two-level
 * config cache. On write-transaction {@code AFTER_COMMIT} (or immediately when
 * no transaction is active), deletes affected Redis L2 keys and publishes
 * invalidation notifications on {@code aicostops:v2:cfg-invalidate}.
 *
 * <p>Redis failures are logged and swallowed so an already-committed Control
 * Plane transaction never fails due to Redis outage. Provider credentials are
 * local-only on Gateway and are therefore only published (never read, written,
 * or stored in Redis).
 */
@Service
public class GatewayConfigInvalidationPublisher {

    public static final String INVALIDATION_CHANNEL = "aicostops:v2:cfg-invalidate";

    public static final String PREFIX_CRED = "cfg:cred:";
    public static final String PREFIX_PRINCIPAL = "cfg:principal:";
    public static final String PREFIX_MODEL = "cfg:model:";
    public static final String PREFIX_ROUTE = "cfg:route:";
    public static final String PREFIX_PRICING = "cfg:pricing:";
    public static final String PREFIX_CONN = "cfg:conn:";

    public static final String TYPE_CRED = "CRED";
    public static final String TYPE_PRINCIPAL = "PRINCIPAL";
    public static final String TYPE_MODEL = "MODEL";
    public static final String TYPE_MODEL_ALL = "MODEL_ALL";
    public static final String TYPE_ROUTE = "ROUTE";
    public static final String TYPE_ROUTE_ORG = "ROUTE_ORG";
    public static final String TYPE_ROUTE_ALL = "ROUTE_ALL";
    public static final String TYPE_PRICING = "PRICING";
    public static final String TYPE_CONN = "CONN";
    public static final String TYPE_PROVIDER_CRED = "PROVIDER_CRED";

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigInvalidationPublisher.class);
    private static final GatewayConfigInvalidationPublisher NOOP =
            new GatewayConfigInvalidationPublisher((StringRedisTemplate) null, null);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Autowired
    public GatewayConfigInvalidationPublisher(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper objectMapper) {
        this(redisProvider == null ? null : redisProvider.getIfAvailable(), objectMapper);
    }

    public GatewayConfigInvalidationPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public static GatewayConfigInvalidationPublisher noop() {
        return NOOP;
    }

    public void invalidateCredential(long credentialId, String digestHex) {
        if (credentialId <= 0 && (digestHex == null || digestHex.isBlank())) {
            return;
        }
        afterCommit(() -> {
            if (digestHex != null && !digestHex.isBlank()) {
                deleteAndPublish(PREFIX_CRED + digestHex, TYPE_CRED, digestHex);
            }
            if (credentialId > 0) {
                var key = Long.toString(credentialId);
                deleteAndPublish(PREFIX_PRINCIPAL + key, TYPE_PRINCIPAL, key);
            }
        });
    }

    public void invalidateCredentials(Collection<CredentialTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        var copy = List.copyOf(targets);
        afterCommit(() -> {
            for (var target : copy) {
                if (target.digestHex() != null && !target.digestHex().isBlank()) {
                    deleteAndPublish(PREFIX_CRED + target.digestHex(), TYPE_CRED, target.digestHex());
                }
                if (target.credentialId() > 0) {
                    var key = Long.toString(target.credentialId());
                    deleteAndPublish(PREFIX_PRINCIPAL + key, TYPE_PRINCIPAL, key);
                }
            }
        });
    }

    public void invalidatePrincipal(long credentialId) {
        if (credentialId <= 0) {
            return;
        }
        var key = Long.toString(credentialId);
        afterCommit(() -> deleteAndPublish(PREFIX_PRINCIPAL + key, TYPE_PRINCIPAL, key));
    }

    public void invalidatePrincipals(Collection<Long> credentialIds) {
        if (credentialIds == null || credentialIds.isEmpty()) {
            return;
        }
        var ids = new LinkedHashSet<>(credentialIds);
        afterCommit(() -> {
            for (var credentialId : ids) {
                if (credentialId != null && credentialId > 0) {
                    var key = Long.toString(credentialId);
                    deleteAndPublish(PREFIX_PRINCIPAL + key, TYPE_PRINCIPAL, key);
                }
            }
        });
    }

    public void invalidateModelCatalog(long orgId) {
        if (orgId <= 0) {
            return;
        }
        var key = Long.toString(orgId);
        afterCommit(() -> deleteAndPublish(PREFIX_MODEL + key, TYPE_MODEL, key));
    }

    public void invalidateAllModelCatalogs() {
        afterCommit(() -> {
            scanAndDelete(PREFIX_MODEL + "*");
            publish(TYPE_MODEL_ALL, "*");
        });
    }

    public void invalidateRoute(long orgId, long modelId) {
        if (orgId <= 0 || modelId <= 0) {
            return;
        }
        var key = orgId + ":" + modelId;
        afterCommit(() -> deleteAndPublish(PREFIX_ROUTE + key, TYPE_ROUTE, key));
    }

    public void invalidateOrgRoutes(long orgId, Collection<Long> knownModelIds) {
        if (orgId <= 0) {
            return;
        }
        var modelIds = knownModelIds == null ? List.<Long>of() : new LinkedHashSet<>(knownModelIds);
        afterCommit(() -> {
            var keysToDelete = new LinkedHashSet<String>();
            for (var modelId : modelIds) {
                if (modelId != null && modelId > 0) {
                    keysToDelete.add(PREFIX_ROUTE + orgId + ":" + modelId);
                }
            }
            if (!keysToDelete.isEmpty()) {
                deleteKeys(keysToDelete);
            }
            scanAndDelete(PREFIX_ROUTE + orgId + ":*");
            publish(TYPE_ROUTE_ORG, Long.toString(orgId));
        });
    }

    public void invalidateAllRoutes() {
        afterCommit(() -> {
            scanAndDelete(PREFIX_ROUTE + "*");
            publish(TYPE_ROUTE_ALL, "*");
        });
    }

    public void invalidatePricingVersion(long pricingVersionId) {
        if (pricingVersionId <= 0) {
            return;
        }
        var key = Long.toString(pricingVersionId);
        afterCommit(() -> deleteAndPublish(PREFIX_PRICING + key, TYPE_PRICING, key));
    }

    public void invalidateConnectionProfile(long connId) {
        if (connId <= 0) {
            return;
        }
        var key = Long.toString(connId);
        afterCommit(() -> deleteAndPublish(PREFIX_CONN + key, TYPE_CONN, key));
    }

    /**
     * Provider credentials are stored ONLY in Gateway local memory; never write
     * or delete Redis data keys for them. Only publish the local eviction signal.
     */
    public void invalidateProviderCredential(long orgId, long providerAccountId) {
        if (orgId <= 0 || providerAccountId <= 0) {
            return;
        }
        var key = orgId + ":" + providerAccountId;
        afterCommit(() -> publish(TYPE_PROVIDER_CRED, key));
    }

    private void afterCommit(Runnable action) {
        if (redis == null || objectMapper == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(action);
                }
            });
        } else {
            safeRun(action);
        }
    }

    private void deleteAndPublish(String redisKey, String type, String messageKey) {
        deleteKeys(List.of(redisKey));
        publish(type, messageKey);
    }

    private void deleteKeys(Collection<String> redisKeys) {
        if (redis == null || redisKeys.isEmpty()) {
            return;
        }
        try {
            redis.delete(redisKeys);
        } catch (RuntimeException ex) {
            log.warn("Best-effort Redis cache delete failed for keys {}", redisKeys, ex);
        }
    }

    private void scanAndDelete(String pattern) {
        if (redis == null) {
            return;
        }
        try {
            var matched = new ArrayList<String>();
            var cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(200).build());
            if (cursor != null) {
                try (cursor) {
                    while (cursor.hasNext()) {
                        matched.add(cursor.next());
                    }
                }
            }
            if (!matched.isEmpty()) {
                redis.delete(matched);
            }
        } catch (RuntimeException ex) {
            log.warn("Best-effort Redis scan-delete failed for pattern {}", pattern, ex);
        }
    }

    private void publish(String type, String key) {
        if (redis == null || objectMapper == null) {
            return;
        }
        try {
            var payload = objectMapper.writeValueAsString(new InvalidationMessage(type, key));
            redis.convertAndSend(INVALIDATION_CHANNEL, payload);
        } catch (RuntimeException ex) {
            log.warn("Best-effort Redis cache invalidation publish failed for type={} key={}",
                    type, key, ex);
        }
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Best-effort Gateway config cache invalidation failed", ex);
        }
    }

    public record CredentialTarget(long credentialId, String digestHex) {
    }

    public record InvalidationMessage(String type, String key) {
    }
}
