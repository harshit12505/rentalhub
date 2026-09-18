package com.rentalhub.web.rest;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos with no storage configured, which is how the default test context runs (no
 * S3_BUCKET). The spec's words: "fail cleanly if S3 isn't configured".
 */
class ImagesSwitchedOffTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    @Test
    @DisplayName("an upload is a clear 503 that says why, with no Retry-After, and the listing is untouched")
    void uploadsFailCleanly() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();

        mvc.perform(multipart("/api/properties/{id}/images", listingId)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                                Files.readAllBytes(Path.of("samples", "api", "photo.jpg"))))
                        .header(ApiHeaders.DEMO_USER_ID, hostId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.messageKey").value("image.storage.notConfigured"))
                .andExpect(jsonPath("$.detail").value(
                        "Photo uploads are switched off, because no image storage is configured on this server."));

        mvc.perform(get("/api/properties/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images").isEmpty());
    }

    @Test
    @DisplayName("serving a photo is a 503 too, rather than a misleading 404")
    void servingFailsCleanly() throws Exception {
        mvc.perform(get("/images/listings/1/0b6b3c3e-6a4f-4f59-9a0e-2b8f3c1d5e7a.jpg"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.messageKey").value("image.storage.notConfigured"));
    }
}
