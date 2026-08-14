package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderNormalizerTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(HeaderNormalizer.normalize("  model  ")).isEqualTo("model");
        assertThat(HeaderNormalizer.normalize("\tuser_id\n")).isEqualTo("user_id");
    }

    @Test
    void stripsUtf8Bom() {
        assertThat(HeaderNormalizer.normalize("\uFEFFstart_time")).isEqualTo("start_time");
    }

    @Test
    void collapsesInternalWhitespaceRuns() {
        assertThat(HeaderNormalizer.normalize("api   key")).isEqualTo("api key");
        assertThat(HeaderNormalizer.normalize("total \t tokens")).isEqualTo("total tokens");
    }

    @Test
    void normalizesUnicodeCompatibilityForms() {
        // Full-width latin letters normalize to ASCII under NFKC.
        assertThat(HeaderNormalizer.normalize("\uFF21\uFF22\uFF23")).isEqualTo("ABC");
    }

    @Test
    void chineseHeadersNormalizeToCanonicalForm() {
        assertThat(HeaderNormalizer.normalize(" 时间范围 ")).isEqualTo("时间范围");
        // Full-width and half-width punctuation normalize to the same canonical form,
        // so either observed export variant matches the same frozen contract header.
        assertThat(HeaderNormalizer.normalize("充值账户消耗（元）")).isEqualTo("充值账户消耗(元)");
        assertThat(HeaderNormalizer.normalize("充值账户消耗(元)")).isEqualTo("充值账户消耗(元)");
    }

    @Test
    void normalizesAllHeadersInOnePass() {
        assertThat(HeaderNormalizer.normalizeAll(List.of(" model ", "api_key", "  ")))
                .containsExactly("model", "api_key", "");
    }
}
