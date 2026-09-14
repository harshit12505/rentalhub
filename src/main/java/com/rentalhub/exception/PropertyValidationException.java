package com.rentalhub.exception;

/**
 * A listing broke one of the factory's rules: either a rule every listing shares or
 * one that belongs to its property type. See {@link InvalidRequestException} for
 * {@code field}.
 */
public class PropertyValidationException extends InvalidRequestException {

    public PropertyValidationException(String messageKey, Object... arguments) {
        super(null, messageKey, arguments);
    }

    private PropertyValidationException(String field, String messageKey, Object[] arguments) {
        super(field, messageKey, arguments);
    }

    public static PropertyValidationException onField(String field, String messageKey, Object... arguments) {
        return new PropertyValidationException(field, messageKey, arguments);
    }
}
