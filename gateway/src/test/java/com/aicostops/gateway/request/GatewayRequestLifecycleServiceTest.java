package com.aicostops.gateway.request;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class GatewayRequestLifecycleServiceTest {

    @Test
    void beginUpstreamLeavesAttemptAtDispatchIntent() {
        var mapper = mock(GatewayRequestMapper.class);
        var blockingIo = mock(BlockingIoScheduler.class);
        when(blockingIo.run(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return Mono.empty();
        });
        var service = new GatewayRequestLifecycleService(mapper, blockingIo);

        service.beginUpstream(10, 20, 30).block();

        verify(mapper).markRequestUpstreamActive(10, 20);
        verify(mapper, never()).markAttemptBillablePossible(30, 20);
    }

    @Test
    void safetyEvidenceUsesBoundedReason() {
        var mapper = mock(GatewayRequestMapper.class);
        var blockingIo = mock(BlockingIoScheduler.class);
        when(blockingIo.run(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return Mono.empty();
        });
        var service = new GatewayRequestLifecycleService(mapper, blockingIo);
        service.markSafe(10, 20, 30, ProviderSafetyReason.DNS_PRE_CONNECT).block();
        verify(mapper).markAttemptSafe(30, 20, "DNS_PRE_CONNECT");
    }
}
