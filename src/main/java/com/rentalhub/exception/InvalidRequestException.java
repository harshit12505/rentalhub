package com.rentalhub.exception;

import lombok.Getter;

/**
 * The request asks for something a business rule forbids. Rendered as HTTP 400.
 *
 * {@code field} names the offending input (for example {@code checkOut} or
 * {@code attributes[plotAreaSqm]}) so a form can show the message next to the right
 * input. It is null when a rule is not about a single field.
 */
@Getter
public class InvalidRequestException extends LocalizedException {

    private final String field;

    public InvalidRequestException(String messageKey, Object... arguments) {
        this(null, messageKey, arguments);
    }

    protected InvalidRequestException(String field, String messageKey, Object[] arguments) {
        super(messageKey, arguments);
        this.field = field;
    }

    public static InvalidRequestException onField(String field, String messageKey, Object... arguments) {
        return new InvalidRequestException(field, messageKey, arguments);
    }
}
