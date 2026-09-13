package com.rentalhub.exception;

/**
 * The acting user may not do this (a guest creating a listing, a host editing someone
 * else's). Rendered as HTTP 403.
 */
public class OperationNotAllowedException extends LocalizedException {

    public OperationNotAllowedException(String messageKey, Object... arguments) {
        super(messageKey, arguments);
    }
}
