package com.aicostops.gateway.budget;

import com.aicostops.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

/**
 * Narrow bridge exposing the reservation/recovery tunables to the budget
 * package. Keeps the reservation code decoupled from the full Gateway
 * property surface so unit tests can substitute bounded values.
 */
@Component
public class GatewayPropertiesBridge {

    private final GatewayProperties properties;

    public GatewayPropertiesBridge(GatewayProperties properties) {
        this.properties = properties;
    }

    public long reservationTtlMs() {
        return properties.getReservationTtlMs();
    }

    public long reservationRecoveryIntervalMs() {
        return properties.getReservationRecoveryIntervalMs();
    }

    public int reservationRecoveryBatchSize() {
        return properties.getReservationRecoveryBatchSize();
    }
}
