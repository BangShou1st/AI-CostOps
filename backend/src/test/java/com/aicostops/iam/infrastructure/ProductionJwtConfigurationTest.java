package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProductionJwtConfigurationTest {
    @Test
    void absentProductionSigningKeyStillFailsClosed() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new JwtTokenService("", Duration.ofMinutes(15), Clock.systemUTC()))
                .withMessageContaining("at least 256 bits");
    }
}
