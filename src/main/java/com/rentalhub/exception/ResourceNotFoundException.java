package com.rentalhub.exception;

/** The thing asked for does not exist. Rendered as HTTP 404. */
public class ResourceNotFoundException extends LocalizedException {

    public ResourceNotFoundException(String messageKey, Object... arguments) {
        super(messageKey, arguments);
    }
}
