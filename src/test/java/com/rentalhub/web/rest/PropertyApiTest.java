package com.rentalhub.web.rest;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API as a client sees it: status codes, JSON shape and error bodies.
 *
 * MockMvc sends requests through the real Spring MVC machinery (JSON conversion,
 * validation, the exception handler) without opening a network port. Not
 * {@code @Transactional}: each request must run in its own transactions exactly as in
 * production, or a lazy-loading bug could hide behind a test-wide transaction.
 */
class PropertyApiTest extends IntegrationTest {

    private static final String HEADER = PropertyController.DEMO_USER_HEADER;

    /** A villa as a client would send it. Attribute values may be plain JSON numbers and booleans. */
    private static final String VILLA_JSON = """
            {
              "type": "VILLA",
              "title": "Sea breeze villa",
              "description": "Four bedrooms, two minutes from the beach.",
              "city": "Goa",
              "country": "India",
              "pricePerNight": 12000.00,
              "currency": "INR",
              "maxGuests": 8,
              "bedrooms": 4,
              "bathrooms": 3,
              "attributes": { "plotAreaSqm": 450, "hasPool": true }
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    private long hostId;
    private long guestId;

    @BeforeEach
    void createUsers() {
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
    }

    @Test
    @DisplayName("POST creates a listing: 201, a Location header, and the listing as JSON")
    void createListing() throws Exception {
        mvc.perform(post("/api/properties").header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON).content(VILLA_JSON))
                .andExpect(status().isCreated())
                // Any id: identity counters are not reset by rolled-back tests, so it need not be 1.
                .andExpect(header().string("Location", matchesRegex(".*/api/properties/\\d+$")))
                .andExpect(jsonPath("$.type").value("VILLA"))
                .andExpect(jsonPath("$.host.fullName").value("Asha Menon"))
                .andExpect(jsonPath("$.attributes.plotAreaSqm").value(450))
                .andExpect(jsonPath("$.attributes.hasPool").value(true));
    }

    @Test
    @DisplayName("GET returns a listing; an unknown id is a 404 problem detail")
    void getListing() throws Exception {
        long id = createVilla();

        mvc.perform(get("/api/properties/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sea breeze villa"));

        mvc.perform(get("/api/properties/{id}", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("There is no listing with id 9999."))
                .andExpect(jsonPath("$.messageKey").value("property.notFound"));
    }

    @Test
    @DisplayName("bean-validation failures list every bad field")
    void validationErrorsListEveryField() throws Exception {
        String invalid = VILLA_JSON.replace("\"Sea breeze villa\"", "\"\"").replace("\"Goa\"", "\" \"");

        mvc.perform(post("/api/properties").header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("city"))
                .andExpect(jsonPath("$.errors[0].message").value("This field is required."))
                .andExpect(jsonPath("$.errors[1].field").value("title"));
    }

    @Test
    @DisplayName("a broken type rule is a 400 naming the field")
    void factoryRuleViolation() throws Exception {
        String tinyVilla = VILLA_JSON.replace("\"maxGuests\": 8", "\"maxGuests\": 2");

        mvc.perform(post("/api/properties").header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON).content(tinyVilla))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("maxGuests"))
                .andExpect(jsonPath("$.messageKey").value("property.villa.guests.min"));
    }

    @Test
    @DisplayName("a guest cannot create a listing, and nobody can call without naming themselves")
    void whoMayCreate() throws Exception {
        mvc.perform(post("/api/properties").header(HEADER, guestId)
                        .contentType(MediaType.APPLICATION_JSON).content(VILLA_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("property.create.notHost"));

        mvc.perform(post("/api/properties")
                        .contentType(MediaType.APPLICATION_JSON).content(VILLA_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT replaces a listing; only its host may do it, and its type cannot change")
    void updateListing() throws Exception {
        long id = createVilla();

        mvc.perform(put("/api/properties/{id}", id).header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VILLA_JSON.replace("Sea breeze villa", "Sunset villa")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sunset villa"))
                .andExpect(jsonPath("$.version").value(1));

        long otherHostId = users.save(new com.rentalhub.domain.model.User(
                "Vikram Rao", "vikram@example.com", com.rentalhub.domain.model.enums.UserRole.HOST)).getId();
        mvc.perform(put("/api/properties/{id}", id).header(HEADER, otherHostId)
                        .contentType(MediaType.APPLICATION_JSON).content(VILLA_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("property.notOwner"));

        mvc.perform(put("/api/properties/{id}", id).header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VILLA_JSON.replace("\"VILLA\"", "\"STUDIO\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messageKey").value("property.type.cannotChange"));
    }

    @Test
    @DisplayName("DELETE removes a listing, unless it has bookings")
    void deleteListing() throws Exception {
        long withBooking = createVilla();
        jdbc.update("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 2, 24000.00, 'INR', 'CONFIRMED')
                """, withBooking, guestId, LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 12));

        mvc.perform(delete("/api/properties/{id}", withBooking).header(HEADER, hostId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("property.delete.hasBookings"));

        long withoutBooking = createVilla();
        mvc.perform(delete("/api/properties/{id}", withoutBooking).header(HEADER, hostId))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/properties/{id}", withoutBooking))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET with filters returns one page of listing cards")
    void search() throws Exception {
        createVilla();

        mvc.perform(get("/api/properties").param("city", "GOA").param("guests", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Sea breeze villa"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/properties").param("city", "Goa").param("guests", "10"))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    private long createVilla() throws Exception {
        MvcResult result = mvc.perform(post("/api/properties").header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON).content(VILLA_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
