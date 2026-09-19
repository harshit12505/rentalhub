package com.rentalhub.web.mvc;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * "Ask for ideas" in this context, which has no Gemini key: the page must say the AI is off and
 * still answer, from the ordinary search. (The AI's own answers are tested with fake models in
 * ai/AiRecommendationTest; the page shows whatever the service returns.)
 */
class RecommendationPageTest extends PageTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    private long guestId;

    @BeforeEach
    void createListings() {
        long hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        properties.create(TestRequests.validVilla(), hostId);
        properties.create(TestRequests.validApartment(), hostId);
    }

    @Test
    @DisplayName("with the AI off the page says so up front, and still answers a signed-in guest from the plain search")
    void answersWithoutTheAi() throws Exception {
        String page = html(mvc.perform(get("/recommendations").param("q", "somewhere in Goa for 4")
                .session(signIn(guestId))));

        assertThat(page).contains("Search by meaning is switched off on this server", "Written by the app itself.",
                        "The listings it matched", "Test villa", "value=\"somewhere in Goa for 4\"")
                .doesNotContain("Test apartment", "ranked by meaning");
    }

    @Test
    @DisplayName("a question from someone not signed in asks them to sign in first, and nothing is searched")
    void signInFirst() throws Exception {
        String page = html(mvc.perform(get("/recommendations").param("q", "somewhere in Goa")));

        assertThat(page).contains("Sign in (top right) first").doesNotContain("The listings it matched");
    }
}
