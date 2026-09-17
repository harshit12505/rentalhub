package com.rentalhub.web.rest;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Reviews over REST. Past stays are inserted with plain SQL: the booking API, rightly,
 * refuses dates in the past.
 */
class ReviewApiTest extends IntegrationTest {

    private static final String HEADER = ApiHeaders.DEMO_USER_ID;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService propertyService;

    private final LocalDate today = LocalDate.now();
    private long hostId;
    private long guestId;
    private long otherGuestId;
    private long listingId;

    @BeforeEach
    void createListing() {
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        otherGuestId = users.save(TestRequests.secondGuest()).getId();
        listingId = propertyService.create(TestRequests.validApartment(), hostId).id();
    }

    @Test
    @DisplayName("a guest whose stay has ended can review the listing, and everyone can read it")
    void reviewAfterAStay() throws Exception {
        stay(guestId, today.minusDays(5), today.minusDays(2), "CONFIRMED");

        review(guestId, 5, "  Lovely flat, great host.  ")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesRegex(".*/api/reviews/\\d+$")))
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Lovely flat, great host."))
                .andExpect(jsonPath("$.author.fullName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.propertyId").value(listingId));

        mvc.perform(get("/api/properties/{id}/reviews", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("only a guest whose stay there has ended may review: not before, not after cancelling, not the host")
    void onlyAfterAStay() throws Exception {
        review(guestId, 5, "Never been").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("review.notStayed"));

        stay(guestId, today.plusDays(10), today.plusDays(12), "CONFIRMED");
        review(guestId, 5, "Not yet").andExpect(status().isForbidden());

        stay(otherGuestId, today.minusDays(9), today.minusDays(7), "CANCELLED");
        review(otherGuestId, 5, "Cancelled").andExpect(status().isForbidden());

        review(hostId, 5, "My own place").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a check-out day of today counts as a finished stay")
    void checkOutToday() throws Exception {
        stay(guestId, today.minusDays(3), today, "CONFIRMED");

        review(guestId, 4, null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("one review per guest per listing")
    void onlyOnce() throws Exception {
        stay(guestId, today.minusDays(5), today.minusDays(2), "CONFIRMED");
        review(guestId, 5, "First").andExpect(status().isCreated());

        review(guestId, 1, "Second thoughts")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("review.alreadyReviewed"));
    }

    @Test
    @DisplayName("the rating must be a whole number from 1 to 5")
    void ratingRange() throws Exception {
        stay(guestId, today.minusDays(5), today.minusDays(2), "CONFIRMED");

        review(guestId, 6, "Off the scale")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rating"))
                .andExpect(jsonPath("$.errors[0].message").value("Rating must be a whole number from 1 to 5."));
    }

    @Test
    @DisplayName("the author can edit and delete their review; nobody else can")
    void editAndDelete() throws Exception {
        long reviewId = reviewAfterStay();

        mvc.perform(put("/api/reviews/{id}", reviewId).header(HEADER, otherGuestId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("review.notAuthor"));
        mvc.perform(put("/api/reviews/{id}", reviewId).header(HEADER, guestId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 3, \"comment\": \"Noisy at night.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(3))
                .andExpect(jsonPath("$.comment").value("Noisy at night."));

        mvc.perform(delete("/api/reviews/{id}", reviewId).header(HEADER, otherGuestId))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/reviews/{id}", reviewId).header(HEADER, guestId))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageKey").value("review.notFound"));
    }

    @Test
    @DisplayName("every change to a review is kept in its history, deletion included")
    void reviewHistory() throws Exception {
        long reviewId = reviewAfterStay();
        mvc.perform(put("/api/reviews/{id}", reviewId).header(HEADER, guestId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 3}")).andExpect(status().isOk());
        mvc.perform(delete("/api/reviews/{id}", reviewId).header(HEADER, guestId)).andExpect(status().isNoContent());

        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT a.revtype, a.rating, r.changed_by FROM reviews_aud a JOIN revinfo r ON r.rev = a.rev
                WHERE a.id = ? ORDER BY a.rev
                """, reviewId);
        // revtype: 0 created, 1 updated, 2 deleted. (The driver hands SMALLINT back as Integer.)
        assertThat(history).extracting(row -> row.get("revtype")).containsExactly(0, 1, 2);
        assertThat(history).extracting(row -> row.get("rating")).containsExactly(5, 3, 3);
        assertThat(history).extracting(row -> row.get("changed_by")).containsOnly("user:" + guestId);
    }

    @Test
    @DisplayName("reviews of a listing that does not exist are a 404")
    void unknownListing() throws Exception {
        mvc.perform(get("/api/properties/{id}/reviews", 9999)).andExpect(status().isNotFound());
    }

    private long reviewAfterStay() throws Exception {
        stay(guestId, today.minusDays(5), today.minusDays(2), "CONFIRMED");
        String location = review(guestId, 5, "Lovely").andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions review(long userId, int rating, String comment) throws Exception {
        String json = comment == null
                ? "{\"rating\": %d}".formatted(rating)
                : "{\"rating\": %d, \"comment\": \"%s\"}".formatted(rating, comment);
        return mvc.perform(post("/api/properties/{id}/reviews", listingId).header(HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void stay(long guest, LocalDate checkIn, LocalDate checkOut, String status) {
        jdbc.update("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 2, 7500.00, 'INR', ?)
                """, listingId, guest, checkIn, checkOut, status);
    }
}
