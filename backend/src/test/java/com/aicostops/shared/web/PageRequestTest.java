package com.aicostops.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PageRequestTest {

    @Test
    void providesContractDefaults() {
        assertThat(PageRequest.defaults()).isEqualTo(PageRequest.of(0, 50));
    }

    @Test
    void rejectsNegativePageAndSizesOutsideContractBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> PageRequest.of(-1, 50));
        assertThatIllegalArgumentException().isThrownBy(() -> PageRequest.of(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> PageRequest.of(0, 201));
    }
}
