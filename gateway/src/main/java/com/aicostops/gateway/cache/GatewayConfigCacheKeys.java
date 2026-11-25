package com.aicostops.gateway.cache;

/** Key conventions and Pub/Sub message contract for Gateway two-level config cache. */
public final class GatewayConfigCacheKeys {

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
    public static final String TYPE_ALL = "ALL";

    private GatewayConfigCacheKeys() {
    }

    public static String credKey(String digestHex) {
        return PREFIX_CRED + digestHex;
    }

    public static String principalKey(long credentialId) {
        return PREFIX_PRINCIPAL + credentialId;
    }

    public static String modelKey(long orgId) {
        return PREFIX_MODEL + orgId;
    }

    public static String routeKey(long orgId, long modelId) {
        return PREFIX_ROUTE + orgId + ":" + modelId;
    }

    public static String pricingKey(long pricingVersionId) {
        return PREFIX_PRICING + pricingVersionId;
    }

    public static String connKey(long connId) {
        return PREFIX_CONN + connId;
    }

    public record ConfigInvalidationMessage(String type, String key) {
    }
}
