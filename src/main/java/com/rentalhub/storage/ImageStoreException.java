package com.rentalhub.storage;

/**
 * The image store could not do what was asked: it is not configured, unreachable, or refused
 * the request (wrong credentials, missing bucket).
 *
 * Deliberately not a LocalizedException: this is a technical failure between two systems, and
 * the service that catches it decides what the user is told.
 */
public class ImageStoreException extends RuntimeException {

    public ImageStoreException(String message) {
        super(message);
    }

    public ImageStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
