package com.rentalhub.storage;

import java.util.Optional;

/**
 * The store used when no bucket is configured.
 *
 * It exists so that "no S3" is an ordinary, testable state rather than a missing bean: the
 * application starts, listings work, and only a photo upload is refused, with a message that
 * says why (the spec's rule: a missing credential costs its own feature and nothing else).
 */
public class UnconfiguredImageStore implements ImageStore {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public void put(String key, byte[] content, ImageFormat format) {
        throw new ImageStoreException("No image storage is configured (S3_BUCKET is not set)");
    }

    @Override
    public Optional<StoredImage> get(String key) {
        return Optional.empty();
    }

    @Override
    public void delete(String key) {
        // Nothing can have been stored, so there is nothing to delete.
    }
}
