package com.aicostops.gateway.config;

import java.util.concurrent.Callable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound runtime configuration for the Gateway data plane. Every limit is
 * mandatory: a non-positive or unbounded value rejects startup before the
 * application can accept a billable request.
 */
@ConfigurationProperties(prefix = "aicostops.gateway")
public class GatewayProperties implements InitializingBean {

    private int port = 8081;
    private String credentialHmacKeyV1 = "";
    private String requestHmacKeyV1 = "";
    private String providerKekV1 = "";
    private boolean devBootstrapEnabled = false;
    private String devRawKey = "";
    private boolean rateLimitEnabled = true;
    private int rateLimitCapacity = 60;
    private double rateLimitRefillPerSecond = 1.0;
    private boolean quotaEnabled = true;
    private long quotaRequestsPerDay = 1000;
    private int dbThreads = 12;
    private int dbQueueCapacity = 256;
    private int dbPoolMax = 12;
    private int maxActiveStreams = 128;
    private int maxRequestBytes = 1048576;
    private int maxHeaderBytes = 16384;
    private int maxInMemoryBytes = 16777216;
    private int connectTimeoutMs = 5000;
    private int headerTimeoutMs = 60000;
    private int streamIdleTimeoutMs = 60000;
    private int hardTimeoutMs = 600000;
    private long reservationTtlMs = 900000;
    private long reservationRecoveryIntervalMs = 60000;
    private int reservationRecoveryBatchSize = 100;
    private int circuitFailureThreshold = 5;
    private long circuitOpenDurationMs = 30000;
    private long circuitHalfOpenLeaseMs = 15000;

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /** Fail-fast guard for every bounded resource the data plane relies on. */
    public void validate() {
        requirePositive("port", port);
        requirePositive("dbThreads", dbThreads);
        requirePositive("dbQueueCapacity", dbQueueCapacity);
        requirePositive("dbPoolMax", dbPoolMax);
        requirePositive("maxActiveStreams", maxActiveStreams);
        requirePositive("maxRequestBytes", maxRequestBytes);
        requirePositive("maxHeaderBytes", maxHeaderBytes);
        requirePositive("maxInMemoryBytes", maxInMemoryBytes);
        requirePositive("connectTimeoutMs", connectTimeoutMs);
        requirePositive("headerTimeoutMs", headerTimeoutMs);
        requirePositive("streamIdleTimeoutMs", streamIdleTimeoutMs);
        requirePositive("hardTimeoutMs", hardTimeoutMs);
        requirePositive("reservationTtlMs", reservationTtlMs);
        requirePositive("reservationRecoveryIntervalMs", reservationRecoveryIntervalMs);
        requirePositive("reservationRecoveryBatchSize", reservationRecoveryBatchSize);
        requirePositive("circuitFailureThreshold", circuitFailureThreshold);
        requirePositive("circuitOpenDurationMs", circuitOpenDurationMs);
        requirePositive("circuitHalfOpenLeaseMs", circuitHalfOpenLeaseMs);
        if (reservationTtlMs <= hardTimeoutMs) {
            throw new IllegalStateException(
                    "aicostops.gateway.reservation-ttl-ms must exceed hard-timeout-ms, got "
                            + reservationTtlMs + " <= " + hardTimeoutMs);
        }
        if (rateLimitEnabled) {
            requirePositive("rateLimitCapacity", rateLimitCapacity);
            requirePositive("rateLimitRefillPerSecond",
                    Math.round(rateLimitRefillPerSecond * 1000));
        }
        if (quotaEnabled) {
            requirePositive("quotaRequestsPerDay", quotaRequestsPerDay);
        }
    }

    private static void requirePositive(String field, long value) {
        if (value <= 0) {
            throw new IllegalStateException(
                    "aicostops.gateway." + field + " must be positive, got " + value);
        }
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getCredentialHmacKeyV1() {
        return credentialHmacKeyV1;
    }

    public void setCredentialHmacKeyV1(String credentialHmacKeyV1) {
        this.credentialHmacKeyV1 = credentialHmacKeyV1;
    }

    public String getRequestHmacKeyV1() {
        return requestHmacKeyV1;
    }

    public void setRequestHmacKeyV1(String requestHmacKeyV1) {
        this.requestHmacKeyV1 = requestHmacKeyV1;
    }

    public String getProviderKekV1() {
        return providerKekV1;
    }

    public void setProviderKekV1(String providerKekV1) {
        this.providerKekV1 = providerKekV1;
    }

    public boolean isDevBootstrapEnabled() {
        return devBootstrapEnabled;
    }

    public void setDevBootstrapEnabled(boolean devBootstrapEnabled) {
        this.devBootstrapEnabled = devBootstrapEnabled;
    }

    public String getDevRawKey() {
        return devRawKey;
    }

    public void setDevRawKey(String devRawKey) {
        this.devRawKey = devRawKey;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public int getRateLimitCapacity() {
        return rateLimitCapacity;
    }

    public void setRateLimitCapacity(int rateLimitCapacity) {
        this.rateLimitCapacity = rateLimitCapacity;
    }

    public double getRateLimitRefillPerSecond() {
        return rateLimitRefillPerSecond;
    }

    public void setRateLimitRefillPerSecond(double rateLimitRefillPerSecond) {
        this.rateLimitRefillPerSecond = rateLimitRefillPerSecond;
    }

    public boolean isQuotaEnabled() {
        return quotaEnabled;
    }

    public void setQuotaEnabled(boolean quotaEnabled) {
        this.quotaEnabled = quotaEnabled;
    }

    public long getQuotaRequestsPerDay() {
        return quotaRequestsPerDay;
    }

    public void setQuotaRequestsPerDay(long quotaRequestsPerDay) {
        this.quotaRequestsPerDay = quotaRequestsPerDay;
    }

    public int getDbThreads() {
        return dbThreads;
    }

    public void setDbThreads(int dbThreads) {
        this.dbThreads = dbThreads;
    }

    public int getDbQueueCapacity() {
        return dbQueueCapacity;
    }

    public void setDbQueueCapacity(int dbQueueCapacity) {
        this.dbQueueCapacity = dbQueueCapacity;
    }

    public int getDbPoolMax() {
        return dbPoolMax;
    }

    public void setDbPoolMax(int dbPoolMax) {
        this.dbPoolMax = dbPoolMax;
    }

    public int getMaxActiveStreams() {
        return maxActiveStreams;
    }

    public void setMaxActiveStreams(int maxActiveStreams) {
        this.maxActiveStreams = maxActiveStreams;
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public int getMaxHeaderBytes() {
        return maxHeaderBytes;
    }

    public void setMaxHeaderBytes(int maxHeaderBytes) {
        this.maxHeaderBytes = maxHeaderBytes;
    }

    public int getMaxInMemoryBytes() {
        return maxInMemoryBytes;
    }

    public void setMaxInMemoryBytes(int maxInMemoryBytes) {
        this.maxInMemoryBytes = maxInMemoryBytes;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getHeaderTimeoutMs() {
        return headerTimeoutMs;
    }

    public void setHeaderTimeoutMs(int headerTimeoutMs) {
        this.headerTimeoutMs = headerTimeoutMs;
    }

    public int getStreamIdleTimeoutMs() {
        return streamIdleTimeoutMs;
    }

    public void setStreamIdleTimeoutMs(int streamIdleTimeoutMs) {
        this.streamIdleTimeoutMs = streamIdleTimeoutMs;
    }

    public int getHardTimeoutMs() {
        return hardTimeoutMs;
    }

    public void setHardTimeoutMs(int hardTimeoutMs) {
        this.hardTimeoutMs = hardTimeoutMs;
    }

    public long getReservationTtlMs() {
        return reservationTtlMs;
    }

    public void setReservationTtlMs(long reservationTtlMs) {
        this.reservationTtlMs = reservationTtlMs;
    }

    public long getReservationRecoveryIntervalMs() {
        return reservationRecoveryIntervalMs;
    }

    public void setReservationRecoveryIntervalMs(long reservationRecoveryIntervalMs) {
        this.reservationRecoveryIntervalMs = reservationRecoveryIntervalMs;
    }

    public int getReservationRecoveryBatchSize() {
        return reservationRecoveryBatchSize;
    }

    public void setReservationRecoveryBatchSize(int reservationRecoveryBatchSize) {
        this.reservationRecoveryBatchSize = reservationRecoveryBatchSize;
    }

    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int value) { this.circuitFailureThreshold = value; }
    public long getCircuitOpenDurationMs() { return circuitOpenDurationMs; }
    public void setCircuitOpenDurationMs(long value) { this.circuitOpenDurationMs = value; }
    public long getCircuitHalfOpenLeaseMs() { return circuitHalfOpenLeaseMs; }
    public void setCircuitHalfOpenLeaseMs(long value) { this.circuitHalfOpenLeaseMs = value; }
}
