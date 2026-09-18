package com.rentalhub.exception;

/**
 * Photos cannot be stored or read: either no storage is configured at all, or the store failed
 * just now. HTTP 503, because neither is the caller's fault.
 *
 * {@code temporary} separates the two, so that only a passing failure tells the client to try
 * again later (Retry-After); retrying against an unconfigured store would never help.
 */
public class ImageStorageUnavailableException extends LocalizedException {

    private final boolean temporary;

    private ImageStorageUnavailableException(String messageKey, boolean temporary) {
        super(messageKey);
        this.temporary = temporary;
    }

    /** No bucket is configured on this server: uploads are switched off. */
    public static ImageStorageUnavailableException notConfigured() {
        return new ImageStorageUnavailableException("image.storage.notConfigured", false);
    }

    /** The store is configured but failed (unreachable, credentials refused, bucket missing). */
    public static ImageStorageUnavailableException failed() {
        return new ImageStorageUnavailableException("image.storage.unavailable", true);
    }

    public boolean isTemporary() {
        return temporary;
    }
}
