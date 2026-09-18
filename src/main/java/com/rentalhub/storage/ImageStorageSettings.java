package com.rentalhub.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Listing photos, bound from {@code rentalhub.images.*} in application.yml.
 *
 * @param maxSize       the largest photo accepted. Checked here as well as by Spring's
 *                      multipart limit, so the two can never silently disagree
 * @param maxPerListing the most photos one listing may have
 * @param s3            where they are stored
 */
@ConfigurationProperties("rentalhub.images")
public record ImageStorageSettings(
        @DefaultValue("5MB") DataSize maxSize,
        @DefaultValue("10") int maxPerListing,
        @DefaultValue S3 s3) {

    /**
     * @param bucket    the bucket name. Empty means no storage: uploads are switched off
     * @param region    the bucket's AWS region
     * @param endpoint  empty for AWS itself; set only for an S3-compatible server such as MinIO
     * @param pathStyle address the bucket as {@code host/bucket} rather than {@code bucket.host},
     *                  which MinIO needs and AWS does not
     * @param accessKey an access key id; with this and the secret empty, the AWS SDK's own
     *                  default credential chain is used instead
     * @param secretKey the matching secret. Never logged
     */
    public record S3(
            String bucket,
            @DefaultValue("ap-south-1") String region,
            String endpoint,
            @DefaultValue("false") boolean pathStyle,
            String accessKey,
            String secretKey) {

        public boolean configured() {
            return bucket != null && !bucket.isBlank();
        }

        public boolean hasEndpoint() {
            return endpoint != null && !endpoint.isBlank();
        }

        public boolean hasStaticCredentials() {
            return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
        }
    }
}
