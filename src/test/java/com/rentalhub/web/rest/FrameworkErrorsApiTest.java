package com.rentalhub.web.rest;

import com.rentalhub.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC's own errors — the ones no service throws — come back as problem details with a
 * sentence from messages.properties, not Spring's built-in English. Each assertion here pins
 * both the key and the position of Spring's arguments in it.
 */
class FrameworkErrorsApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("a missing acting-user header names the header")
    void missingHeader() throws Exception {
        mvc.perform(get("/api/bookings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The request was not valid"))
                .andExpect(jsonPath("$.detail").value("The X-Demo-User-Id header is required."));
    }

    @Test
    @DisplayName("a missing query parameter names the parameter")
    void missingParameter() throws Exception {
        mvc.perform(get("/api/recommendations").header(ApiHeaders.DEMO_USER_ID, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The q parameter is required."));
    }

    @Test
    @DisplayName("a value of the wrong type names the value and the parameter")
    void typeMismatch() throws Exception {
        mvc.perform(get("/api/properties").param("currency", "XYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("\"XYZ\" is not a valid value for currency."));
        mvc.perform(get("/api/properties/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("\"abc\" is not a valid value for id."));
    }

    @Test
    @DisplayName("a body that is not JSON is refused without echoing the parser's error")
    void unreadableBody() throws Exception {
        mvc.perform(post("/api/properties").header(ApiHeaders.DEMO_USER_ID, 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "The request body could not be read. Check that it is valid JSON with the expected fields."));
    }

    @Test
    @DisplayName("the wrong method and the wrong content type are named")
    void wrongMethodAndType() throws Exception {
        mvc.perform(patch("/api/properties/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("Method not allowed"))
                .andExpect(jsonPath("$.detail").value("The PATCH method is not supported here."));
        mvc.perform(post("/api/properties").header(ApiHeaders.DEMO_USER_ID, 1)
                        .contentType(MediaType.TEXT_PLAIN).content("villa"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Unsupported format"))
                .andExpect(jsonPath("$.detail").value("The content type text/plain;charset=UTF-8 is not supported here."));
    }

    @Test
    @DisplayName("an API path that does not exist is a translated 404 too")
    void unknownPath() throws Exception {
        mvc.perform(get("/api/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("There is nothing at /api/nothing-here."));
    }

    @Test
    @DisplayName("an upload with no file part names the part")
    void missingFilePart() throws Exception {
        mvc.perform(multipart("/api/properties/1/images").header(ApiHeaders.DEMO_USER_ID, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The form must include a part called file."));
    }
}
