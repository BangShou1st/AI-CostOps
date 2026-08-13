package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class DefaultAuthenticationBootstrapTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void nonDevProfileHasNoAuthenticationBootstrapRunner() {
        assertThat(Arrays.stream(applicationContext.getBeanNamesForType(ApplicationRunner.class)))
                .noneMatch(name -> name.toLowerCase().contains("authenticationbootstrap"));
    }
}
