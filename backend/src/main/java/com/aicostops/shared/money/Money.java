package com.aicostops.shared.money;

import java.math.BigDecimal;
import java.util.Objects;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record Money(
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
        CurrencyCode currency) {

    public Money {
        Objects.requireNonNull(amount, "Amount is required");
        Objects.requireNonNull(currency, "Currency is required");
    }

    public static Money of(BigDecimal amount, CurrencyCode currency) {
        return new Money(amount, currency);
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "Money to add is required");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Money currencies must match");
        }
        return new Money(amount.add(other.amount), currency);
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof Money other)) {
            return false;
        }
        return currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }
}
