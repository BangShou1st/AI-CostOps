package com.aicostops.gatewaysettlement.application;

/**
 * Consumer-owned read seam: whether an immutable M15 gateway financial
 * resolution already exists for a request. The Gateway Settlement application
 * never depends on the reconciliation package.
 */
public interface GatewayFinancialTerminalPort {

    boolean hasTerminalResolution(long organizationId, long requestId);
}
