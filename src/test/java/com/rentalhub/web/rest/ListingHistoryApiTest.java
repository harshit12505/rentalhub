package com.rentalhub.web.rest;

import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.UserRole;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.scheduling.StaleListingJob;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A listing's history over REST: each change, who made it, and what changed. Every
 * change here is made through the API, so the audit trail records the real acting user.
 */
class ListingHistoryApiTest extends IntegrationTest {

    private static final String HEADER = ApiHeaders.DEMO_USER_ID;

    private static final String VILLA_JSON = """
            {
              "type": "VILLA", "title": "Sea breeze villa", "description": "Four bedrooms near the beach.",
              "city": "Goa", "country": "India", "pricePerNight": 12000.00, "currency": "INR",
              "maxGuests": 8, "bedrooms": 4, "bathrooms": 3,
              "attributes": { "plotAreaSqm": 450, "hasPool": true }
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private StaleListingJob job;

    private long hostId;
    private long otherHostId;
    private long guestId;

    @BeforeEach
    void createUsers() {
        hostId = users.save(TestRequests.host()).getId();
        otherHostId = users.save(new User("Vikram Rao", "vikram@example.com", UserRole.HOST)).getId();
        guestId = users.save(TestRequests.guest()).getId();
    }

    @Test
    @DisplayName("each change is listed in order, with who made it and what changed field by field")
    void createThenEdit() throws Exception {
        long id = createVilla();
        mvc.perform(put("/api/properties/{id}", id).header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VILLA_JSON.replace("12000.00", "13500.00")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/properties/{id}/history", id).header(HEADER, hostId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[0].changedBy").value("user:" + hostId))
                .andExpect(jsonPath("$[0].changedByName").value("Asha Menon"))
                .andExpect(jsonPath("$[0].changes[?(@.field == 'title')].to").value(hasItem("Sea breeze villa")))
                .andExpect(jsonPath("$[0].changes[?(@.field == 'attributes[hasPool]')].to").value(hasItem(true)))
                .andExpect(jsonPath("$[1].type").value("UPDATED"))
                .andExpect(jsonPath("$[1].changes", hasSize(1)))
                .andExpect(jsonPath("$[1].changes[0].field").value("pricePerNight"))
                .andExpect(jsonPath("$[1].changes[0].from").value(12000.0))
                .andExpect(jsonPath("$[1].changes[0].to").value(13500.0));
    }

    @Test
    @DisplayName("a booking raises the listing's version but adds nothing to its history")
    void bookingsStayOutOfTheListingsHistory() throws Exception {
        long id = createVilla();
        LocalDate checkIn = LocalDate.now().plusDays(30);
        mvc.perform(post("/api/bookings").header(HEADER, guestId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId": %d, "checkIn": "%s", "checkOut": "%s", "guests": 2}
                                """.formatted(id, checkIn, checkIn.plusDays(2))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/properties/{id}/history", id).header(HEADER, hostId))
                .andExpect(jsonPath("$", hasSize(1)));
        // The booking has a history of its own, recorded as the guest's doing.
        String bookedBy = jdbc.queryForObject(
                "SELECT r.changed_by FROM bookings_aud a JOIN revinfo r ON r.rev = a.rev", String.class);
        org.assertj.core.api.Assertions.assertThat(bookedBy).isEqualTo("user:" + guestId);
    }

    @Test
    @DisplayName("a change made by the nightly job is recorded as the job's, not a person's")
    void jobChangesAreAttributedToTheJob() throws Exception {
        long id = createVilla();
        jdbc.update("UPDATE properties SET available_until = ? WHERE id = ?", LocalDate.now().minusDays(1), id);

        job.deactivateExpiredListings();

        mvc.perform(get("/api/properties/{id}/history", id).header(HEADER, hostId))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].changedBy").value("system:" + StaleListingJob.NAME))
                .andExpect(jsonPath("$[1].changedByName").doesNotExist())
                .andExpect(jsonPath("$[1].changes[?(@.field == 'active')].to").value(hasItem(false)));
    }

    @Test
    @DisplayName("only the host may read it, and still can after deleting the listing")
    void whoMayReadIt() throws Exception {
        long id = createVilla();

        mvc.perform(get("/api/properties/{id}/history", id).header(HEADER, otherHostId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("property.history.notHost"));
        mvc.perform(get("/api/properties/{id}/history", 9999).header(HEADER, hostId))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/properties/{id}", id).header(HEADER, hostId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/properties/{id}/history", id).header(HEADER, hostId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].type").value("DELETED"))
                .andExpect(jsonPath("$[1].changes", hasSize(0)));
    }

    @Test
    @DisplayName("a listing with no recorded history (created before auditing began) has an empty history")
    void listingWithoutHistory() throws Exception {
        long id = createVilla();
        jdbc.update("DELETE FROM properties_aud WHERE id = ?", id);   // as if it pre-dated V2

        mvc.perform(get("/api/properties/{id}/history", id).header(HEADER, hostId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/properties/{id}/history", id).header(HEADER, otherHostId))
                .andExpect(status().isForbidden());
    }

    private long createVilla() throws Exception {
        String location = mvc.perform(post("/api/properties").header(HEADER, hostId)
                        .contentType(MediaType.APPLICATION_JSON).content(VILLA_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
