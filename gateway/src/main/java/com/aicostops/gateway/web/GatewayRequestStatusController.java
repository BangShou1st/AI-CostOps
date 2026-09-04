package com.aicostops.gateway.web;

import com.aicostops.gateway.auth.GatewayBearerWebFilter;
import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bounded recovery status surface (AIC-092 section 20): only the owning
 * credential sees a request; a nonexistent request and a request owned by a
 * different credential both return the same privacy-preserving 404. M12
 * returns {@code meteringStatus=null} and {@code settlementStatus=null}
 * because usage facts and Settlement arrive in M13. Never returns prompt,
 * completion, Provider secrets, Budget totals or Ledger detail.
 */
@RestController
@RequestMapping(path = "/v1")
public class GatewayRequestStatusController {

    private final GatewayRequestMapper requestMapper;
    private final BlockingIoScheduler blockingIo;

    public GatewayRequestStatusController(
            GatewayRequestMapper requestMapper, BlockingIoScheduler blockingIo) {
        this.requestMapper = requestMapper;
        this.blockingIo = blockingIo;
    }

    @GetMapping(path = "/gateway/requests/{requestId}")
    public Mono<ResponseEntity<Map<String, Object>>> getRequestStatus(
            @PathVariable("requestId") String requestId, ServerWebExchange exchange) {
        return principal(exchange)
                .flatMap(principal -> blockingIo.call(() -> readStatus(principal, requestId)))
                .map(ResponseEntity::ok);
    }

    private Map<String, Object> readStatus(GatewayPrincipal principal, String requestId) {
        var row = requestMapper.findRequestStatus(requestId, principal.organizationId());
        if (row == null || row.credentialId() != principal.credentialId()) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_NOT_FOUND,
                    "Request not found");
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", row.publicRequestId());
        body.put("requestState", row.state());
        body.put("meteringStatus", null);
        body.put("settlementStatus", null);
        body.put("createdAt", row.createdAt());
        body.put("updatedAt", row.updatedAt());
        return body;
    }

    private static Mono<GatewayPrincipal> principal(ServerWebExchange exchange) {
        var principal = exchange.getAttribute(GatewayBearerWebFilter.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof GatewayPrincipal gatewayPrincipal) {
            return Mono.just(gatewayPrincipal);
        }
        return Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID,
                "A valid Gateway key is required"));
    }
}