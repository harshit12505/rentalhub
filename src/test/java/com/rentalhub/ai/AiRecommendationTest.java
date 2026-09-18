package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.scheduling.EmbeddingIndexJob;
import com.rentalhub.service.FavoriteService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.ConnectedIntegrationTest;
import com.rentalhub.support.FakeAiModels;
import com.rentalhub.support.TestMessages;
import com.rentalhub.support.TestRequests;
import com.rentalhub.web.rest.ApiHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The AI features with the models faked and everything else real.
 *
 * In the connected context (see ConnectedIntegrationTest), because it needs an embedding model
 * and a chat model where the default context deliberately has none. Everything under them is
 * the real thing: the real PgVectorStore, the real pgvector container, real SQL filters and the
 * real grounding check. What is faked (see support/FakeAiModels) is only the part that would otherwise need
 * a key, a network and quota.
 */
class AiRecommendationTest extends ConnectedIntegrationTest {

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

    @Autowired
    private ListingEmbeddingService embeddings;

    @Autowired
    private FakeAiModels.FakeChatModel chatModel;

    private long hostId;
    private long guestId;

    @BeforeEach
    void createUsers() {
        chatModel.behave();
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
    }

    @Test
    @DisplayName("a new listing is embedded as soon as its transaction commits")
    void listingsAreIndexedOnChange() {
        assertThat(availability.ready()).isTrue();

        long quiet = listing("Quiet garden cottage", "A calm garden hideaway, far from any noise.", "Goa");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_embeddings", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT document_id FROM listing_embeddings WHERE property_id = ?", String.class, quiet))
                .as("the document id is derived from the listing id, so it never duplicates")
                .isEqualTo(ListingEmbeddingService.documentIdFor(quiet).toString());
        assertThat(indexJob.indexMissingListings())
                .as("nothing is left for the backfill job")
                .isEmpty();
    }

    @Test
    @DisplayName("a listing is re-embedded only when the text it embeds has changed")
    void reEmbedsOnlyWhatChanged() {
        long id = listing("Quiet garden cottage", "A calm garden hideaway.", "Goa");
        String firstHash = hashOf(id);

        assertThat(embeddings.index(id))
                .as("indexing it again changes nothing, so no call is made")
                .isFalse();
        assertThat(hashOf(id)).isEqualTo(firstHash);

        PropertyRequest edited = villa("Quiet garden cottage", "A calm garden hideaway, now with a hammock.", "Goa");
        properties.update(id, edited, hostId);

        assertThat(hashOf(id)).isNotEqualTo(firstHash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class))
                .as("still one document for one listing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a listing that leaves the site leaves the index with it")
    void deletedListingsAreForgotten() {
        long id = listing("Quiet garden cottage", "A calm garden hideaway.", "Goa");
        properties.delete(id, hostId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_embeddings", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class)).isZero();
    }

    @Test
    @DisplayName("the words of the question find the listing that means the same, and the model only sees those")
    void searchesByMeaning() throws Exception {
        long quiet = listing("Quiet garden cottage", "A calm garden hideaway, far from any noise.", "Goa");
        long loud = listing("Party house by the clubs", "Loud music, dancing and busy clubs all night.", "Goa");

        mvc.perform(get("/api/recommendations")
                        .param("q", "somewhere calm with a garden, away from noise")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semantic").value(true))
                .andExpect(jsonPath("$.aiUsed").value(true))
                .andExpect(jsonPath("$.suggestions[0].listing.id").value(quiet))
                .andExpect(jsonPath("$.suggestions[0].similarity").isNumber())
                .andExpect(jsonPath("$.answer").value("I would take [" + quiet + "] for this trip."));

        assertThat(chatModel.lastPrompt())
                .as("the model may only talk about the listings it was handed")
                .contains("[" + quiet + "]")
                .contains("[" + loud + "]")
                .doesNotContain("[999]");
    }

    @Test
    @DisplayName("the crisp parts of the question are still SQL: a budget keeps out what does not fit")
    void filtersAreStillReal() throws Exception {
        listing("Quiet garden cottage", "A calm garden hideaway, far from any noise.", "Goa");

        mvc.perform(get("/api/recommendations")
                        .param("q", "somewhere calm with a garden under 5000")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                // The listing means exactly the right thing and costs 12,000 a night.
                .andExpect(jsonPath("$.suggestions", hasSize(0)))
                .andExpect(jsonPath("$.answer").value(TestMessages.english("ai.noMatches")));
    }

    @Test
    @DisplayName("\"like my favourites\" is answered by the average of their embeddings, computed in SQL")
    void findsMoreLikeTheSavedOnes() throws Exception {
        long savedOne = listing("Quiet garden cottage", "A calm garden hideaway, far from any noise.", "Goa");
        long savedTwo = listing("Peaceful garden cabin", "A calm garden retreat, far from any noise.", "Manali");
        long alike = listing("Calm garden retreat", "A quiet garden house, away from the noise.", "Chennai");
        long unlike = listing("Party house by the clubs", "Loud music, dancing and busy clubs all night.", "Goa");

        favorites.save(savedOne, guestId);
        favorites.save(savedTwo, guestId);

        mvc.perform(get("/api/recommendations")
                        .param("q", "somewhere like my favourites")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semantic").value(true))
                .andExpect(jsonPath("$.suggestions", hasSize(2)))
                .andExpect(jsonPath("$.suggestions[0].listing.id").value(alike))
                .andExpect(jsonPath("$.suggestions[1].listing.id").value(unlike));

        assertThat(chatModel.lastPrompt())
                .as("the listings they already saved are not suggested back to them")
                .doesNotContain("[" + savedOne + "]")
                .doesNotContain("[" + savedTwo + "]");
    }

    @Test
    @DisplayName("a model that invents a listing is overruled, and the guest still gets real ones")
    void inventedListingsNeverReachTheGuest() throws Exception {
        long real = listing("Quiet garden cottage", "A calm garden hideaway, far from any noise.", "Goa");
        chatModel.willSay("You will love [424242], right on the beach, for 900 a night.");

        mvc.perform(get("/api/recommendations")
                        .param("q", "somewhere calm with a garden")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.answer").value(TestMessages.english("ai.listOnly", 1)))
                .andExpect(jsonPath("$.suggestions", hasSize(1)))
                .andExpect(jsonPath("$.suggestions[0].listing.id").value(real));
    }

    @Test
    @DisplayName("when the model fails, the listings still arrive with a plain answer")
    void survivesAModelOutage() throws Exception {
        long real = listing("Quiet garden cottage", "A calm garden hideaway, far from any noise.", "Goa");
        chatModel.willAnswer(prompt -> {
            throw new IllegalStateException("429 quota exceeded");
        });

        mvc.perform(get("/api/recommendations")
                        .param("q", "somewhere calm with a garden")
                        .header(ApiHeaders.DEMO_USER_ID, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.semantic").value(true))
                .andExpect(jsonPath("$.suggestions[0].listing.id").value(real))
                .andExpect(jsonPath("$.answer").value(TestMessages.english("ai.listOnly", 1)));
    }

    private long listing(String title, String description, String city) {
        return properties.create(villa(title, description, city), hostId).id();
    }

    private PropertyRequest villa(String title, String description, String city) {
        PropertyRequest request = TestRequests.base(PropertyType.VILLA);
        request.setTitle(title);
        request.setDescription(description);
        request.setCity(city);
        request.setPricePerNight(new java.math.BigDecimal("12000.00"));
        request.setMaxGuests(6);
        request.setBedrooms(3);
        request.getAttributes().put("plotAreaSqm", "450.00");
        request.getAttributes().put("hasPool", "true");
        return request;
    }

    private String hashOf(long propertyId) {
        return jdbc.queryForObject("SELECT content_hash FROM listing_embeddings WHERE property_id = ?",
                String.class, propertyId);
    }
}
