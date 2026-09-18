package com.rentalhub.ai;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.scheduling.EmbeddingIndexJob;
import com.rentalhub.service.FavoriteService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestMessages;
import com.rentalhub.support.TestRequests;
import com.rentalhub.web.rest.ApiHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application with no Gemini key at all, which is how this whole context runs.
 *
 * This is the requirement the spec is firmest about: no credential may be needed to boot, and
 * a missing one may only cost you its own feature. So the chat model, the embedding model and
 * the vector store beans do not exist here (see AiEnvironmentPostProcessor), listings are
 * created and read as usual, and asking a question still returns listings and a clear reason.
 */
class AiSwitchedOffTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    @Autowired
    private FavoriteService favorites;

    @Autowired
    private AiAvailability availability;

    @Autowired
    private EmbeddingIndexJob indexJob;

    @Test
    @DisplayName("with no key, the app boots, listings are created, and nothing is indexed")
    void everythingElseStillWorks() {
        assertThat(availability.ready()).isFalse();
        assertThat(availability.canSearch()).isFalse();
        assertThat(availability.canWrite()).isFalse();
        assertThat(availability.reason()).isEqualTo("ai.notConfigured");

        long hostId = users.save(TestRequests.host()).getId();
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();

        assertThat(properties.getListing(listingId).title())
                .as("creating a listing must not depend on an AI key")
                .isEqualTo("Test villa");
        assertThat(indexJob.indexMissingListings())
                .as("the backfill job has nothing to embed with")
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_embeddings", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class)).isZero();
    }

    @Test
    @DisplayName("a question is still answered: real listings, from SQL, and a reason for the plain answer")
    void questionsAreAnsweredWithoutAModel() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        long guestId = users.save(TestRequests.guest()).getId();
        properties.create(TestRequests.validVilla(), hostId);

        mvc.perform(get("/api/recommendations")
                        .param("q", "somewhere quiet in Goa for 2")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("RECOMMEND"))
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.semantic").value(false))
                .andExpect(jsonPath("$.suggestions", hasSize(1)))
                .andExpect(jsonPath("$.suggestions[0].listing.city").value("Goa"))
                .andExpect(jsonPath("$.suggestions[0].similarity").doesNotExist())
                .andExpect(jsonPath("$.answer").value(TestMessages.english("ai.notConfigured", 1)));
    }

    @Test
    @DisplayName("statistics questions are SQL either way, so they answer exactly with no key")
    void statisticsNeedNoModel() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        long guestId = users.save(TestRequests.guest()).getId();
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();
        favorites.save(listingId, guestId);

        mvc.perform(get("/api/recommendations")
                        .param("q", "how many listings have I saved?")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("STATS"))
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.suggestions", hasSize(0)))
                .andExpect(jsonPath("$.answer")
                        .value(TestMessages.english("ai.stats.favourites.cities", 1, "Goa")));
    }

    @Test
    @DisplayName("a guest is never recommended their own listing")
    void neverYourOwnListing() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        properties.create(TestRequests.validVilla(), hostId);

        mvc.perform(get("/api/recommendations")
                        .param("q", "anywhere in Goa")
                        .header(ApiHeaders.DEMO_USER_ID, hostId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions", hasSize(0)))
                .andExpect(jsonPath("$.answer").value(TestMessages.english("ai.noMatches")));
    }

    @Test
    @DisplayName("saving a listing with no key does not break the write path")
    void favouritesWorkWithoutAi() throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        long guestId = users.save(TestRequests.guest()).getId();
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();

        mvc.perform(put("/api/properties/{id}/favorite", listingId)
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isNoContent());
    }
}
