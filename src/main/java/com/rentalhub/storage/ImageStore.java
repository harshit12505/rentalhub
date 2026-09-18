package com.rentalhub.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * Where listing photos are kept: an S3 bucket (or anything that speaks S3, such as MinIO), or
 * nowhere at all when none is configured.
 *
 * Behind an interface for the same reason as payment/PaymentGateway: the rest of the app
 * decides what may be uploaded and in what order things happen; this only moves bytes.
 * Implementations throw {@link ImageStoreException} when the store fails, and never a
 * provider's own exception type, so nothing outside this package knows it is S3.
 */
public interface ImageStore {

    /** False when no storage is configured: uploads are refused with a clear message. */
    boolean configured();

    /** Stores the bytes under this key, replacing anything already there. */
    void put(String key, byte[] content, ImageFormat format);

    /** The stored image, or empty if there is nothing under this key. The caller closes the stream. */
    Optional<StoredImage> get(String key);

    /** Removes the object. Removing one that is not there is not an error. */
    void delete(String key);

    /**
     * An image as read back from the store.
     *
     * @param content     the bytes, as a stream, so a large photo never has to sit in memory
     * @param contentType the type recorded when it was stored
     * @param length      its size in bytes
     */
    record StoredImage(InputStream content, String contentType, long length) {
    }
}
