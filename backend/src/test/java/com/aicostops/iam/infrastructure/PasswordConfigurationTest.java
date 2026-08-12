package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordConfigurationTest {

    @Test
    void delegatesPasswordEncodingAndVerifiesWithoutCustomCryptography() {
        var encoder = new PasswordConfiguration().passwordEncoder();

        var encoded = encoder.encode("correct horse battery staple");

        assertThat(encoded).startsWith("{bcrypt}");
        assertThat(encoder.matches("correct horse battery staple", encoded)).isTrue();
        assertThat(encoder.matches("wrong", encoded)).isFalse();
    }
}
