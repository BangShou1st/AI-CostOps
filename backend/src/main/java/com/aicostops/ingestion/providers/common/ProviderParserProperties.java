package com.aicostops.ingestion.providers.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI CostOps safety defaults for provider archive parsing. These are implementation
 * configuration, not provider facts: bounded expansion, entry count and compression
 * ratio defend against ZIP-bomb style archives before any provider row is parsed.
 */
@ConfigurationProperties(prefix = "aicostops.ingestion.provider-parser")
public record ProviderParserProperties(
        int maxArchiveEntries,
        long maxExpandedBytes,
        double maxCompressionRatio,
        long compressionRatioCheckAfterBytes) {

    public ProviderParserProperties {
        if (maxArchiveEntries <= 0) {
            throw new IllegalArgumentException("aicostops.ingestion.provider-parser.max-archive-entries must be positive");
        }
        if (maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("aicostops.ingestion.provider-parser.max-expanded-bytes must be positive");
        }
        if (!(maxCompressionRatio > 0) || Double.isNaN(maxCompressionRatio) || Double.isInfinite(maxCompressionRatio)) {
            throw new IllegalArgumentException("aicostops.ingestion.provider-parser.max-compression-ratio must be a positive finite number");
        }
        if (compressionRatioCheckAfterBytes <= 0) {
            throw new IllegalArgumentException("aicostops.ingestion.provider-parser.compression-ratio-check-after-bytes must be positive");
        }
    }
}
