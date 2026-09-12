package com.rentalhub.factory;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The value types a type-specific attribute can have.
 *
 * Each constant knows how to parse its own raw text (from a form field or JSON), so
 * a new kind is a new constant here rather than a new branch in a switch elsewhere.
 * {@link #parse} throws {@link IllegalArgumentException} (NumberFormatException is
 * one) for text it cannot read; {@link TypeAttributes} turns that into a validation
 * error the user can see.
 */
public enum AttributeKind {

    INTEGER(Integer.class) {
        @Override
        Object parse(String raw) {
            return Integer.valueOf(raw);
        }
    },

    DECIMAL(BigDecimal.class) {
        @Override
        Object parse(String raw) {
            return new BigDecimal(raw);
        }
    },

    /**
     * Strictly "true" or "false". A checkbox cannot express "not stated", and some
     * rules (a high-floor apartment must say whether it has a lift) depend on that
     * difference, so forms offer an explicit yes / no / blank choice.
     */
    BOOLEAN(Boolean.class) {
        @Override
        Object parse(String raw) {
            if (raw.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (raw.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Not a boolean: " + raw);
        }
    },

    /**
     * One code from a fixed list, normalised to upper case. Locale.ROOT matters:
     * under a Turkish default locale, "electric".toUpperCase() is "ELECTRİC" with a
     * dotted capital I, which would never match the code "ELECTRIC".
     */
    CHOICE(String.class) {
        @Override
        Object parse(String raw) {
            return raw.toUpperCase(Locale.ROOT);
        }
    };

    private final Class<?> javaType;

    AttributeKind(Class<?> javaType) {
        this.javaType = javaType;
    }

    /** The Java type {@link #parse} returns. */
    public Class<?> javaType() {
        return javaType;
    }

    /** @param raw non-blank text with surrounding whitespace already removed */
    abstract Object parse(String raw);
}
