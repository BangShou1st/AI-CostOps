package com.aicostops.gatewayadmin.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Serializes a {@link BigDecimal} as an exact scale-8 decimal string.
 *
 * <p>{@link BigDecimal#toString()} may switch to scientific notation for
 * values with an adjusted exponent below -6 (for example {@code 0.00000001}
 * becomes {@code 1E-8}), which violates the Control Plane money contract.
 * This serializer always emits the plain scale-8 representation and fails
 * closed if the value cannot be represented exactly at scale 8.
 */
public final class Scale8DecimalSerializer extends StdSerializer<BigDecimal> {

    public Scale8DecimalSerializer() {
        super(BigDecimal.class);
    }

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context)
            throws JacksonException {
        if (value == null) {
            generator.writeNull();
            return;
        }
        generator.writeString(value.setScale(8, RoundingMode.UNNECESSARY).toPlainString());
    }
}
