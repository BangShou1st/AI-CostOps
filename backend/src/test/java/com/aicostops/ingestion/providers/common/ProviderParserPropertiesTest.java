package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderParserPropertiesTest {

    @Test
    void productionDefaultsArePositiveAndSane() {
        var defaults = new ProviderParserProperties(64, 1_073_741_824L, 100.0d, 1_048_576L);

        assertThat(defaults.maxArchiveEntries()).isEqualTo(64);
        assertThat(defaults.maxExpandedBytes()).isEqualTo(1_073_741_824L);
        assertThat(defaults.maxCompressionRatio()).isEqualTo(100.0d);
        assertThat(defaults.compressionRatioCheckAfterBytes()).isEqualTo(1_048_576L);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new ProviderParserProperties(0, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-archive-entries");
        assertThatThrownBy(() -> new ProviderParserProperties(1, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-expanded-bytes");
        assertThatThrownBy(() -> new ProviderParserProperties(1, 1, 0.0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-compression-ratio");
        assertThatThrownBy(() -> new ProviderParserProperties(1, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compression-ratio-check-after-bytes");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderParserProperties(1, 1, Double.NaN, 1));
    }
}
