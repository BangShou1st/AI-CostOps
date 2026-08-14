package com.aicostops.ingestion.providers.common;

import java.text.Normalizer;
import java.util.List;

/**
 * Header comparison normalization shared by every provider schema matcher.
 *
 * <p>Trims, strips the UTF-8 BOM, collapses whitespace runs and applies Unicode
 * NFKC so provider headers compare deterministically. Raw header text remains
 * available to row maps; only comparison uses this normalization.
 */
public final class HeaderNormalizer {

    private HeaderNormalizer() {
    }

    public static String normalize(String header) {
        if (header == null) {
            return "";
        }
        var stripped = header.startsWith("\uFEFF") ? header.substring(1) : header;
        var composed = Normalizer.normalize(stripped, Normalizer.Form.NFKC);
        return composed.trim().replaceAll("\\s+", " ");
    }

    public static List<String> normalizeAll(List<String> headers) {
        return headers.stream().map(HeaderNormalizer::normalize).toList();
    }
}
