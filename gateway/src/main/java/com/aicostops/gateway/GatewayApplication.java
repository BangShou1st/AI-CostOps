package com.aicostops.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI CostOps V2 Data Plane. WebFlux + Reactor Netty edge process; blocking
 * JDBC/MyBatis seams are offloaded to a dedicated bounded scheduler.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}