package com.rentalhub.config;

import com.rentalhub.storage.ImageStorageSettings;
import com.rentalhub.storage.ImageStore;
import com.rentalhub.storage.S3ImageStore;
import com.rentalhub.storage.UnconfiguredImageStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Chooses where listing photos go: an S3 bucket when {@code S3_BUCKET} is set, otherwise
 * nowhere, and uploads are refused with a clear message.
 *
 * Building the client makes no network call, so a wrong key or a missing bucket cannot stop
 * the application starting; they show up as a failed upload instead, and are logged. A setting
 * that cannot even be read (an endpoint that is not a URL) switches storage off with a warning,
 * for the same reason: the rule is that the application always starts.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageStorageSettings.class)
public class S3Config {

    /** Long enough for a 5 MB photo on a slow link, short enough that a dead store never hangs a request. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    ImageStore imageStore(ImageStorageSettings settings) {
        ImageStorageSettings.S3 s3 = settings.s3();
        if (!s3.configured()) {
            log.atInfo().setMessage("images.mode")
                    .addKeyValue("storage", "NONE")
                    .addKeyValue("reason", "no S3_BUCKET: photo uploads are switched off")
                    .log();
            return new UnconfiguredImageStore();
        }
        try {
            S3Client client = client(s3);
            log.atInfo().setMessage("images.mode")
                    .addKeyValue("storage", "S3")
                    .addKeyValue("bucket", s3.bucket())
                    .addKeyValue("region", s3.region())
                    .addKeyValue("endpoint", s3.hasEndpoint() ? s3.endpoint() : "aws")
                    .addKeyValue("credentials", s3.hasStaticCredentials() ? "access key" : "default chain")
                    .log();
            return new S3ImageStore(client, s3.bucket());
        } catch (RuntimeException unreadable) {
            log.atWarn().setMessage("images.mode")
                    .addKeyValue("storage", "NONE")
                    .addKeyValue("reason", "the S3 settings could not be used: " + unreadable.getMessage())
                    .log();
            return new UnconfiguredImageStore();
        }
    }

    private static S3Client client(ImageStorageSettings.S3 s3) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.region()))
                .forcePathStyle(s3.pathStyle())
                // Checksums only where S3 insists: the newer "always" default is not
                // understood by every S3-compatible server.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(CALL_TIMEOUT)
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .build());
        if (s3.hasEndpoint()) {
            builder.endpointOverride(URI.create(s3.endpoint()));
        }
        builder.credentialsProvider(s3.hasStaticCredentials()
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey()))
                : DefaultCredentialsProvider.builder().build());
        return builder.build();
    }
}
