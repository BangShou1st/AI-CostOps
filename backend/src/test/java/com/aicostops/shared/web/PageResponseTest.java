package com.aicostops.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void roundsTotalPagesUpForPartialLastPage() {
        var response = PageResponse.of(List.of("item"), PageRequest.of(1, 50), 51);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.totalElements()).isEqualTo(51);
        assertThat(response.totalPages()).isEqualTo(2);
    }

    @Test
    void reportsZeroPagesForAnEmptyResult() {
        assertThat(PageResponse.of(List.of(), PageRequest.defaults(), 0).totalPages()).isZero();
    }
}
