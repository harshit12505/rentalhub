package com.rentalhub.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * Listing photos in an S3 bucket, or in anything that speaks the S3 API (MinIO in the tests).
 *
 * The bucket stays private: nothing here makes an object public. Photos reach browsers through
 * the application's own {@code /images/...} endpoint instead, which is why no public-access
 * setting on the bucket has to be changed, and why a URL in a cached listing never expires.
 */
public class S3ImageStore implements ImageStore, AutoCloseable {

    /**
     * Keys are never reused (each upload gets a new random one), so what is under a key never
     * changes, and anything in between may keep a copy for a year.
     */
    static final String CACHE_FOREVER = "public, max-age=31536000, immutable";

    private final S3Client client;
    private final String bucket;

    public S3ImageStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void put(String key, byte[] content, ImageFormat format) {
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(format.mediaType())
                            .cacheControl(CACHE_FOREVER)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException failed) {
            throw new ImageStoreException("Could not store " + key + " in bucket " + bucket, failed);
        }
    }

    @Override
    public Optional<StoredImage> get(String key) {
        try {
            ResponseInputStream<GetObjectResponse> object = client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            GetObjectResponse response = object.response();
            return Optional.of(new StoredImage(object, response.contentType(), response.contentLength()));
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (SdkException failed) {
            throw new ImageStoreException("Could not read " + key + " from bucket " + bucket, failed);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException failed) {
            throw new ImageStoreException("Could not delete " + key + " from bucket " + bucket, failed);
        }
    }

    /** Spring calls this at shutdown, which releases the client's HTTP connection pool. */
    @Override
    public void close() {
        client.close();
    }
}
