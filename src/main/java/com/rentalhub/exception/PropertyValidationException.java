package com.rentalhub.exception;

import lombok.Getter;

/**
 * A listing broke one of the factory's rules: either a rule every listing shares or
 * one that belongs to its property type.
 *
 * {@code field} names the offending form field (for example {@code maxGuests} or
 * {@code attributes[plotAreaSqm]}) so a form can show the message next to the right
 * input. It is null when a rule is not about a single field.
 */
@Getter
public class PropertyValidationException extends LocalizedException {

    private final String field;

    public PropertyValidationException(String messageKey, Object... arguments) {
        this(null, messageKey, arguments);
    }

    private PropertyValidationException(String field, String messageKey, Object[] arguments) {
        super(messageKey, arguments);
        this.field = field;
    }

    public static PropertyValidationException onField(String field, String messageKey, Object... arguments) {
        return new PropertyValidationException(field, messageKey, arguments);
    }
}
