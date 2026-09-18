package com.rentalhub.web.rest;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saving listings. Favourites exist for their own sake, and because the AI phase builds a
 * guest's taste out of them.
 */
class FavoriteApiTest extends IntegrationTest {

    private static final String HEADER = ApiHeaders.DEMO_USER_ID;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    private long guestId;
    private long listingId;

    @BeforeEach
    void createListing() {
        long hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        listingId = properties.create(TestRequests.validVilla(), hostId).id();
    }

    @Test
    @DisplayName("saving twice leaves one favourite, and removing one that is not saved is not an error")
    void savingIsIdempotent() throws Exception {
        mvc.perform(put("/api/properties/{id}/favorite", listingId).header(HEADER, guestId))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/properties/{id}/favorite", listingId).header(HEADER, guestId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/favorites").header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].listing.id").value(listingId))
                .andExpect(jsonPath("$[0].listing.title").value("Test villa"))
                .andExpect(jsonPath("$[0].savedAt").exists());

        mvc.perform(delete("/api/properties/{id}/favorite", listingId).header(HEADER, guestId))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/properties/{id}/favorite", listingId).header(HEADER, guestId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/favorites").header(HEADER, guestId))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("a favourites list can show its prices in another currency, without storing them")
    void showsConvertedPrices() throws Exception {
        mvc.perform(put("/api/properties/{id}/favorite", listingId).header(HEADER, guestId));

        mvc.perform(get("/api/favorites").param("currency", "USD").header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].listing.pricePerNight").value(12000.00))
                .andExpect(jsonPath("$[0].listing.currency").value("INR"))
                // 1 USD = 80 INR in the tests, so ₹12,000 is exactly $150.
                .andExpect(jsonPath("$[0].listing.displayPrice.amount").value(150.00))
                .andExpect(jsonPath("$[0].listing.displayPrice.currency").value("USD"));
    }

    @Test
    @DisplayName("saving a listing that does not exist is a 404, not a silent no-op")
    void unknownListing() throws Exception {
        mvc.perform(put("/api/properties/{id}/favorite", 9999).header(HEADER, guestId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageKey").value("property.notFound"));
    }
}
