package com.rentalhub.web.rest;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.service.FavoriteService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same API in English, Hindi and Spanish: chosen by Accept-Language, overridden by
 * ?lang=, remembered in a cookie — and every kind of message follows it: business rules, bean
 * validation, Spring's own errors, labels inside messages, and the AI's statistics answers.
 */
class LanguageApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    @Autowired
    private FavoriteService favorites;

    @Test
    @DisplayName("Accept-Language chooses the language, region and all; anything unsupported gets English")
    void acceptLanguage() throws Exception {
        mvc.perform(get("/api/properties/999").header(HttpHeaders.ACCEPT_LANGUAGE, "hi-IN,hi;q=0.9,en;q=0.8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("नहीं मिला"))
                .andExpect(jsonPath("$.detail").value("आईडी 999 वाली कोई लिस्टिंग नहीं है।"));

        mvc.perform(get("/api/properties/999").header(HttpHeaders.ACCEPT_LANGUAGE, "es-MX"))
                .andExpect(jsonPath("$.detail").value("No existe ningún anuncio con id 999."));

        mvc.perform(get("/api/properties/999").header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR,fr;q=0.9"))
                .andExpect(jsonPath("$.detail").value("There is no listing with id 999."));
    }

    @Test
    @DisplayName("?lang= beats Accept-Language, and is remembered in a cookie for the next request")
    void langParameterWinsAndSticks() throws Exception {
        MvcResult chosen = mvc.perform(get("/api/properties/999").param("lang", "es")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "hi"))
                .andExpect(jsonPath("$.detail").value("No existe ningún anuncio con id 999."))
                .andExpect(cookie().value("rentalhub-lang", "es"))
                .andReturn();
        Cookie remembered = chosen.getResponse().getCookie("rentalhub-lang");

        mvc.perform(get("/api/properties/999").cookie(remembered).header(HttpHeaders.ACCEPT_LANGUAGE, "hi"))
                .andExpect(jsonPath("$.detail").value("No existe ningún anuncio con id 999."));
    }

    @Test
    @DisplayName("?lang= works even where no controller is reached, and on an upload without reading it early")
    void langParameterEverywhere() throws Exception {
        mvc.perform(patch("/api/properties/1").param("lang", "es"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("Método no permitido"))
                .andExpect(jsonPath("$.detail").value("El método PATCH no se admite aquí."));

        long hostId = users.save(TestRequests.host()).getId();
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();
        mvc.perform(multipart("/api/properties/{id}/images?lang=hi", listingId)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .header(ApiHeaders.DEMO_USER_ID, hostId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail")
                        .value("फ़ोटो अपलोड बंद हैं, क्योंकि इस सर्वर पर कोई इमेज स्टोरेज कॉन्फ़िगर नहीं है।"));
    }

    @Test
    @DisplayName("a language the app does not speak is ignored, not remembered")
    void unsupportedLangIsIgnored() throws Exception {
        MvcResult result = mvc.perform(get("/api/properties/999").param("lang", "fr"))
                .andExpect(jsonPath("$.detail").value("There is no listing with id 999."))
                .andReturn();
        assertThat(result.getResponse().getCookie("rentalhub-lang")).isNull();
    }

    @Test
    @DisplayName("bean validation messages and the error summary follow the language")
    void validationInSpanish() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();

        mvc.perform(post("/api/properties").param("lang", "es").header(ApiHeaders.DEMO_USER_ID, hostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("samples", "api", "invalid-blank-fields.json"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("La solicitud no es válida"))
                .andExpect(jsonPath("$.detail").value("Algunos campos no son válidos."))
                .andExpect(jsonPath("$.errors[0].message").value("Este campo es obligatorio."));
    }

    @Test
    @DisplayName("a label inside a message is translated too: the attribute's name, in Hindi, inside a Hindi sentence")
    void nestedLabelInHindi() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        PropertyRequest villa = TestRequests.validVilla();
        villa.getAttributes().remove("plotAreaSqm");

        mvc.perform(post("/api/properties").header(HttpHeaders.ACCEPT_LANGUAGE, "hi")
                        .header(ApiHeaders.DEMO_USER_ID, hostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "VILLA", "title": "Test villa", "description": "A pleasant place to stay.",
                                 "city": "Goa", "country": "India", "pricePerNight": 12000, "currency": "INR",
                                 "maxGuests": 6, "bedrooms": 3, "bathrooms": 1, "attributes": {"hasPool": "true"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("attributes[plotAreaSqm]"))
                .andExpect(jsonPath("$.detail").value("इस प्रकार की संपत्ति के लिए प्लॉट का क्षेत्रफल (m²) ज़रूरी है।"));
    }

    @Test
    @DisplayName("Spring's own errors are translated, title and detail")
    void frameworkErrorInHindi() throws Exception {
        mvc.perform(get("/api/bookings").header(HttpHeaders.ACCEPT_LANGUAGE, "hi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("अनुरोध मान्य नहीं था"))
                .andExpect(jsonPath("$.detail").value("X-Demo-User-Id हेडर ज़रूरी है।"));
    }

    @Test
    @DisplayName("the AI's answers are messages too, so they follow the language")
    void statisticsInSpanish() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        long guestId = users.save(TestRequests.guest()).getId();
        favorites.save(properties.create(TestRequests.validVilla(), hostId).id(), guestId);

        mvc.perform(get("/api/recommendations").param("q", "how many listings have I saved?").param("lang", "es")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Has guardado 1 anuncio(s), sobre todo en Goa."));
    }
}
