package com.aicostops.shared.web;

import java.util.List;
import java.util.Objects;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        long totalPages) {

    public PageResponse {
        items = List.copyOf(Objects.requireNonNull(items, "Page items are required"));
        if (totalElements < 0) {
            throw new IllegalArgumentException("Total elements must be zero or greater");
        }
    }

    public static <T> PageResponse<T> of(List<T> items, PageRequest request, long totalElements) {
        Objects.requireNonNull(request, "Page request is required");
        var totalPages = totalElements == 0 ? 0 : ((totalElements - 1) / request.size()) + 1;
        return new PageResponse<>(items, request.page(), request.size(), totalElements, totalPages);
    }
}
