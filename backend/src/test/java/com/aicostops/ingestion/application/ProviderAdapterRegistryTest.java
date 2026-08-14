package com.aicostops.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderAdapterRegistryTest {

    @Test
    void resolvesAdapterByCanonicalProviderCodeIgnoringCaseAndWhitespace() {
        var registry = new ProviderAdapterRegistry(List.of(new SyntheticAdapter("DEEPSEEK", "deepseek-usage-zip-v1")));

        assertThat(registry.findByCode("  deepseek ").orElseThrow().parserVersion())
                .isEqualTo("deepseek-usage-zip-v1");
        assertThat(registry.findByCode("DeepSeek")).isPresent();
    }

    @Test
    void unknownProviderCodeResolvesEmpty() {
        var registry = new ProviderAdapterRegistry(List.of());

        assertThat(registry.findByCode("KIMI")).isEmpty();
    }

    @Test
    void emptyRegistryIsAllowedInGroupOne() {
        var registry = new ProviderAdapterRegistry(List.of());

        assertThat(registry.findByCode("DEEPSEEK")).isEmpty();
    }

    @Test
    void duplicateCanonicalRegistrationIsAStartupError() {
        assertThatThrownBy(() -> new ProviderAdapterRegistry(List.of(
                new SyntheticAdapter("DEEPSEEK", "v1"),
                new SyntheticAdapter(" deepseek ", "v2"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK");
    }

    @Test
    void distinctProviderCodesRegisterWithoutConflict() {
        var registry = new ProviderAdapterRegistry(List.of(
                new SyntheticAdapter("DEEPSEEK", "v1"),
                new SyntheticAdapter("MIMO", "v1")));

        assertThat(registry.findByCode("DEEPSEEK")).isPresent();
        assertThat(registry.findByCode("MIMO")).isPresent();
    }

    private record SyntheticAdapter(String providerCode, String parserVersion) implements ProviderAdapter {

        @Override
        public String providerCode() {
            return providerCode;
        }

        @Override
        public String parserVersion() {
            return parserVersion;
        }

        @Override
        public InspectionResult inspect(ProviderSource source) {
            return new InspectionResult(providerCode, "fingerprint", true, List.of());
        }

        @Override
        public void parse(ProviderSource source, InspectionResult inspection, ProviderRecordSink sink) {
        }

        @Override
        public NormalizedProviderRecord normalize(ParsedProviderRecord record) {
            return new NormalizedProviderRecord(record.index(), record.locator(), null,
                    Map.of(), Map.of(), null, null, RawRecordNormalizeStatus.NORMALIZED, List.of());
        }
    }
}
