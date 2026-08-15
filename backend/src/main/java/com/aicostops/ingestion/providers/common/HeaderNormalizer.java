package com.aicostops.ingestion.providers.common;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Normalized header names that collide within one header row. Callers must treat
     * any collision as a schema ERROR: a row map keyed by raw headers would otherwise
     * silently overwrite one column with another.
     */
    public static Set<String> duplicateNormalizedHeaders(List<String> rawHeaders) {
        var seen = new HashSet<String>();
        var duplicates = new HashSet<String>();
        for (var raw : rawHeaders) {
            if (!seen.add(normalize(raw))) {
                duplicates.add(normalize(raw));
            }
        }
        return duplicates;
    }
}
