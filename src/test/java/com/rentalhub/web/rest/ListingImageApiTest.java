package com.rentalhub.web.rest;

import com.jayway.jsonpath.JsonPath;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.ConnectedIntegrationTest;
import com.rentalhub.support.MinioContainerConfiguration;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing photos over REST, stored in a real S3-compatible server (MinIO).
 *
 * The bucket is inspected directly after each step, behind the application's back, because
 * "the row is gone" is not the same as "the file is gone", and the whole design is about
 * keeping those two in step.
 */
class ListingImageApiTest extends ConnectedIntegrationTest {

    private static final String HEADER = ApiHeaders.DEMO_USER_ID;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    @Autowired
    private MinIOContainer minio;

    private S3Client bucket;
    private long hostId;
    private long guestId;
    private long listingId;

    @BeforeEach
    void createListing() {
        bucket = MinioContainerConfiguration.clientFor(minio);
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        listingId = properties.create(TestRequests.validVilla(), hostId).id();
    }

    @AfterEach
    void emptyBucket() {
        objects().forEach(key -> bucket.deleteObject(request -> request.bucket(MinioContainerConfiguration.BUCKET).key(key)));
        bucket.close();
    }

    @Test
    @DisplayName("a host uploads a photo: stored in the bucket, shown on the listing, served back byte for byte")
    void uploadAndServe() throws Exception {
        byte[] jpeg = sample("photo.jpg");

        MvcResult created = upload(hostId, new MockMultipartFile("file", "beach.jpg", "image/jpeg", jpeg))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        matchesRegex(".*/images/listings/" + listingId + "/[0-9a-f-]{36}\\.jpg$")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.url", startsWith("/images/listings/" + listingId + "/")))
                .andReturn();
        String url = JsonPath.read(created.getResponse().getContentAsString(), "$.url");

        assertThat(objects()).containsExactly(url.substring("/images/".length()));

        mvc.perform(get("/api/properties/{id}", listingId))
                .andExpect(jsonPath("$.images", hasSize(1)))
                .andExpect(jsonPath("$.images[0].url").value(url));

        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
                .andExpect(content().bytes(jpeg));
    }

    @Test
    @DisplayName("the bytes decide the type: a renamed PDF is refused, and so is a PNG that claims to be a JPEG")
    void checksTheBytesNotTheName() throws Exception {
        upload(hostId, new MockMultipartFile("file", "photo.jpg", "image/jpeg", sample("not-a-photo.jpg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("image.type.unsupported"))
                .andExpect(jsonPath("$.field").value("file"));

        upload(hostId, new MockMultipartFile("file", "photo.jpg", "image/jpeg", sample("photo.png")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("image.type.mismatch"));

        assertThat(objects()).as("nothing refused was stored").isEmpty();
    }

    @Test
    @DisplayName("with no declared type the bytes alone decide, and the stored type and extension follow them")
    void unlabelledUploadsAreJudgedByContent() throws Exception {
        upload(hostId, new MockMultipartFile("file", "whatever.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                sample("photo.webp")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", matchesRegex(".*\\.webp$")));

        String key = objects().getFirst();
        assertThat(bucket.headObject(request -> request.bucket(MinioContainerConfiguration.BUCKET).key(key))
                .contentType()).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("an empty file or one over 5 MB is refused before anything is stored")
    void refusesEmptyAndOversizedFiles() throws Exception {
        upload(hostId, new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("image.file.empty"));

        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(sample("photo.jpg"), 0, tooBig, 0, 3);
        upload(hostId, new MockMultipartFile("file", "huge.jpg", "image/jpeg", tooBig))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("image.file.tooLarge"))
                .andExpect(jsonPath("$.detail").value("A photo can be at most 5 MB."));

        assertThat(objects()).isEmpty();
    }

    @Test
    @DisplayName("only the listing's host may add or remove photos, and only to a listing that exists")
    void onlyTheHost() throws Exception {
        upload(guestId, new MockMultipartFile("file", "photo.jpg", "image/jpeg", sample("photo.jpg")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("property.notOwner"));

        mvc.perform(multipart("/api/properties/{id}/images", 9999)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", sample("photo.jpg")))
                        .header(HEADER, hostId))
                .andExpect(status().isNotFound());

        assertThat(objects()).isEmpty();
    }

    @Test
    @DisplayName("a listing takes at most 10 photos, numbered in the order they arrive")
    void photoLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            upload(hostId, new MockMultipartFile("file", "p.png", "image/png", sample("photo.png")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sortOrder").value(i));
        }
        upload(hostId, new MockMultipartFile("file", "p.png", "image/png", sample("photo.png")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("image.limit"));

        assertThat(objects()).hasSize(10);
    }

    @Test
    @DisplayName("removing a photo removes its row, then its file, and the listing stops showing it")
    void removeAPhoto() throws Exception {
        MvcResult created = upload(hostId, new MockMultipartFile("file", "photo.jpg", "image/jpeg", sample("photo.jpg")))
                .andReturn();
        String body = created.getResponse().getContentAsString();
        int imageId = JsonPath.read(body, "$.id");
        String url = JsonPath.read(body, "$.url");
        mvc.perform(get("/api/properties/{id}", listingId)).andExpect(jsonPath("$.images", hasSize(1)));

        mvc.perform(delete("/api/properties/{id}/images/{imageId}", listingId, imageId).header(HEADER, guestId))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/properties/{id}/images/{imageId}", listingId, imageId).header(HEADER, hostId))
                .andExpect(status().isNoContent());

        assertThat(objects()).isEmpty();
        mvc.perform(get("/api/properties/{id}", listingId)).andExpect(jsonPath("$.images", hasSize(0)));
        mvc.perform(get(url))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageKey").value("image.file.notFound"));
        mvc.perform(delete("/api/properties/{id}/images/{imageId}", listingId, imageId).header(HEADER, hostId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageKey").value("image.notFound"));
    }

    @Test
    @DisplayName("deleting a listing deletes its photo files too")
    void deletingTheListingDeletesItsFiles() throws Exception {
        upload(hostId, new MockMultipartFile("file", "a.jpg", "image/jpeg", sample("photo.jpg")));
        upload(hostId, new MockMultipartFile("file", "b.png", "image/png", sample("photo.png")));
        assertThat(objects()).hasSize(2);

        mvc.perform(delete("/api/properties/{id}", listingId).header(HEADER, hostId))
                .andExpect(status().isNoContent());

        assertThat(objects()).isEmpty();
    }

    @Test
    @DisplayName("a path that is not one of this app's photo keys is not served at all")
    void servesOnlyItsOwnKeys() throws Exception {
        mvc.perform(get("/images/secrets/passwords.txt")).andExpect(status().isNotFound());
        mvc.perform(get("/images/listings/1/../../secret.jpg")).andExpect(status().isNotFound());
    }

    private ResultActions upload(long userId, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/properties/{id}/images", listingId).file(file).header(HEADER, userId));
    }

    private List<String> objects() {
        return bucket.listObjectsV2(request -> request.bucket(MinioContainerConfiguration.BUCKET))
                .contents().stream().map(S3Object::key).toList();
    }

    private static byte[] sample(String name) throws Exception {
        return Files.readAllBytes(Path.of("samples", "api", name));
    }
}
