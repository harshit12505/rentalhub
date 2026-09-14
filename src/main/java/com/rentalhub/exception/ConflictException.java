package com.rentalhub.exception;

/**
 * The request is valid, but the current state of the data does not allow it (deleting
 * a listing that has bookings). Rendered as HTTP 409.
 */
public class ConflictException extends LocalizedException {

    public ConflictException(String messageKey, Object... arguments) {
        super(messageKey, arguments);
    }
}
