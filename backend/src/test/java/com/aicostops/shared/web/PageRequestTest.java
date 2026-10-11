package com.aicostops.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PageRequestTest {

    @Test
    void providesContractDefaults() {
        assertThat(PageRequest.defaults()).isEqualTo(PageRequest.of(0, 50));
    }

    @Test
    void rejectsNegativePageAndSizesOutsideContractBounds() {
        assertInvalid(-1, 50);
        assertInvalid(0, 0);
        assertInvalid(0, 201);
    }

    @Test
    void rejectsOffsetsThatWouldOverflowAndAcceptsLargestRepresentableOffset() {
        assertInvalid(50_000_000, 50);
        assertThat(PageRequest.of(Integer.MAX_VALUE, 1).offset()).isEqualTo(Integer.MAX_VALUE);
        assertThat(PageRequest.of(10, 200).offset()).isEqualTo(2000);
    }

    private static void assertInvalid(int page, int size) {
        assertThatThrownBy(() -> PageRequest.of(page, size)).isInstanceOfSatisfying(
                DomainException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });
    }
}
