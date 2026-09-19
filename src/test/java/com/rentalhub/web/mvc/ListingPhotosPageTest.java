package com.rentalhub.web.mvc;

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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The host's photo tools on the listing page, with storage switched on (MinIO): upload, see the
 * photo on the page and in the bucket, remove it again.
 *
 * In the connected context, so it cannot extend PageTest; the few helpers it needs are inline.
 */
class ListingPhotosPageTest extends ConnectedIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    @Autowired
    private MinIOContainer minio;

    private S3Client bucket;
    private long listingId;
    private MockHttpSession host;

    @BeforeEach
    void createListing() throws Exception {
        bucket = MinioContainerConfiguration.clientFor(minio);
        long hostId = users.save(TestRequests.host()).getId();
        listingId = properties.create(TestRequests.validVilla(), hostId).id();
        host = (MockHttpSession) mvc.perform(post("/session/user").param("userId", String.valueOf(hostId)))
                .andReturn().getRequest().getSession(false);
    }

    @AfterEach
    void emptyBucket() {
        bucket.listObjectsV2(request -> request.bucket(MinioContainerConfiguration.BUCKET)).contents().stream()
                .map(S3Object::key)
                .forEach(key -> bucket.deleteObject(request -> request.bucket(MinioContainerConfiguration.BUCKET).key(key)));
        bucket.close();
    }

    @Test
    @DisplayName("a host uploads a photo from the page, sees it in the gallery, and removes it again")
    void uploadAndRemove() throws Exception {
        byte[] jpeg = Files.readAllBytes(Path.of("samples", "api", "photo.jpg"));

        MvcResult uploaded = mvc.perform(multipart("/listings/{id}/photos", listingId).session(host)
                        .file(new MockMultipartFile("file", "garden.jpg", "image/jpeg", jpeg)))
                .andExpect(redirectedUrl("/listings/" + listingId + "#photos"))
                .andReturn();

        String page = page(uploaded);
        assertThat(page).contains("Photo added.", "src=\"/images/listings/" + listingId + "/", "Remove");
        assertThat(bucket.listObjectsV2(request -> request.bucket(MinioContainerConfiguration.BUCKET)).contents()).hasSize(1);

        long imageId = jdbc.queryForObject("SELECT id FROM property_images", Long.class);
        MvcResult removed = mvc.perform(post("/listings/{id}/photos/{imageId}/delete", listingId, imageId).session(host))
                .andExpect(redirectedUrl("/listings/" + listingId + "#photos"))
                .andReturn();

        assertThat(page(removed)).contains("Photo removed.", "No photo yet");
        assertThat(bucket.listObjectsV2(request -> request.bucket(MinioContainerConfiguration.BUCKET)).contents())
                .as("the file goes after the row, once the removal has committed")
                .isEmpty();
    }

    @Test
    @DisplayName("a file that is not a photo is refused with the reason on the listing page")
    void notAPhoto() throws Exception {
        MvcResult refused = mvc.perform(multipart("/listings/{id}/photos", listingId).session(host)
                        .file(new MockMultipartFile("file", "notes.jpg", "image/jpeg",
                                "just some text".getBytes(StandardCharsets.UTF_8))))
                .andExpect(redirectedUrl("/listings/" + listingId + "#photos"))
                .andReturn();

        assertThat(page(refused)).contains("alert-danger", "JPEG");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM property_images", Integer.class)).isZero();
    }

    private String page(MvcResult redirect) throws Exception {
        String html = mvc.perform(get(redirect.getResponse().getRedirectedUrl()).session(host)
                        .flashAttrs(redirect.getFlashMap()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("??");
        return html;
    }
}
