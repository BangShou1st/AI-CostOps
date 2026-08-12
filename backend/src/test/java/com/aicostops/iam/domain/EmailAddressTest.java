package com.aicostops.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class EmailAddressTest {

    @Test
    void trimsAndLowercasesWithLocaleIndependentRules() {
        var previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(EmailAddress.normalize("  USER@EXAMPLE.COM  ")).isEqualTo("user@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsMissingOrMalformedEmailAddresses() {
        assertThatIllegalArgumentException().isThrownBy(() -> EmailAddress.normalize(null));
        assertThatIllegalArgumentException().isThrownBy(() -> EmailAddress.normalize(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> EmailAddress.normalize("not-an-email"));
    }
}
