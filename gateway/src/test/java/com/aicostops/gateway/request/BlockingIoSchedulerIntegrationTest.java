package com.aicostops.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * AIC-094 blocking-boundary proof: the dedicated DB scheduler is used for
 * blocking JDBC work, never the Reactor Netty event loop.
 */
@SpringBootTest
@Tag("integration")
class BlockingIoSchedulerIntegrationTest extends GatewayMySqlContainerSupport {

    @Autowired
    private com.aicostops.gateway.config.BlockingIoScheduler blockingIo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void blockingDbWorkRunsOnDedicatedSchedulerNotEventLoop() {
        Mono<String> threadName = blockingIo.call(() -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Thread.currentThread().getName();
        });

        StepVerifier.create(threadName)
                .assertNext(name -> {
                    assertThat(name).startsWith("gateway-db-");
                    assertThat(name).doesNotStartWith("reactor-http-");
                })
                .verifyComplete();
    }

    @Test
    void runOperatorCompletesAndExecutesOnDedicatedScheduler() {
        var holder = new String[1];
        StepVerifier.create(blockingIo.run(() ->
                        holder[0] = Thread.currentThread().getName()))
                .verifyComplete();
        assertThat(holder[0]).startsWith("gateway-db-");
    }
}