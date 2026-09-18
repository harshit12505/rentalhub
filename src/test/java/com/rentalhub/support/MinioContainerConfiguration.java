package com.rentalhub.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * A real S3-compatible server for the tests: MinIO, in Docker.
 *
 * Image storage is tested against the real AWS SDK talking the real S3 protocol, with no AWS
 * account, no bucket to pay for and no network. The only things that differ from production
 * are the endpoint and path-style addressing, which are exactly the two settings that exist for
 * S3-compatible servers.
 *
 * The image comes from quay.io and is pinned: MinIO no longer publishes on Docker Hub, and its
 * community image stopped at this release.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MinioContainerConfiguration {

    public static final String BUCKET = "rentalhub-test-photos";
    private static final String REGION = "us-east-1";

    @Bean
    MinIOContainer minio() {
        MinIOContainer minio = new MinIOContainer(
                DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                        .asCompatibleSubstituteFor("minio/minio"));
        minio.start();
        try (S3Client client = clientFor(minio)) {
            client.createBucket(request -> request.bucket(BUCKET));
        }
        return minio;
    }

    @Bean
    DynamicPropertyRegistrar minioProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("rentalhub.images.s3.bucket", () -> BUCKET);
            registry.add("rentalhub.images.s3.region", () -> REGION);
            registry.add("rentalhub.images.s3.endpoint", minio::getS3URL);
            registry.add("rentalhub.images.s3.path-style", () -> "true");
            registry.add("rentalhub.images.s3.access-key", minio::getUserName);
            registry.add("rentalhub.images.s3.secret-key", minio::getPassword);
        };
    }

    /** A client of the tests' own, to look inside the bucket behind the application's back. */
    public static S3Client clientFor(MinIOContainer minio) {
        return S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .region(Region.of(REGION))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .build();
    }
}
