package com.rentalhub.web.mvc;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.ReviewRequest;
import com.rentalhub.service.PropertyService;
import com.rentalhub.service.ReviewService;
import com.rentalhub.support.TestRequests;
import com.rentalhub.web.DemoSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The home page, the navbar's "sign in as" and language menus, and the error page. */
class HomePageTest extends PageTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    @Autowired
    private ReviewService reviews;

    private long hostId;
    private long guestId;

    @BeforeEach
    void createUsers() {
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
    }

    @Test
    @DisplayName("the home page shows each listing as a card: type, title, price and its rating, with no missing text")
    void cards() throws Exception {
        long villaId = properties.create(TestRequests.validVilla(), hostId).id();
        properties.create(TestRequests.validApartment(), hostId);
        long secondGuestId = users.save(TestRequests.secondGuest()).getId();
        pastStay(villaId, guestId, 20);
        pastStay(villaId, secondGuestId, 10);
        reviews.create(villaId, review(5), guestId);
        reviews.create(villaId, review(4), secondGuestId);

        String page = html(mvc.perform(get("/")));

        assertThat(page).contains("2 place(s) found", "Test villa", "Test apartment",
                "12,000.00 INR", "2,500.00 INR", "4.5 (2 review(s))", "No reviews yet",
                "href=\"/listings/" + villaId + "\"");
    }

    @Test
    @DisplayName("filters are kept in the search form and in the links to the next page")
    void pagesKeepTheFilters() throws Exception {
        for (int i = 0; i < 13; i++) {
            properties.create(TestRequests.validApartment(), hostId);
        }

        // The filters in the address itself, as a browser sends a GET form: the page links are built from it.
        String first = html(mvc.perform(get("/?city=Chennai&guests=2")));

        assertThat(first).contains("13 place(s) found", "Page 1 of 2", "value=\"Chennai\"",
                "href=\"/?city=Chennai&amp;guests=2&amp;page=1\"");
        String second = html(mvc.perform(get("/?city=Chennai&guests=2&page=1")));
        assertThat(second).contains("Page 2 of 2", "href=\"/?city=Chennai&amp;guests=2&amp;page=0\"");
    }

    @Test
    @DisplayName("?lang=es shows the page in Spanish, and the language menu links to this same page in each language")
    void spanish() throws Exception {
        String page = html(mvc.perform(get("/?city=Goa&lang=es")));

        assertThat(page).contains("<html lang=\"es\"", "Encuentra un lugar donde alojarte", "Entrar como",
                "href=\"/?city=Goa&amp;lang=hi\"", "हिन्दी", "Español");
    }

    @Test
    @DisplayName("sign in as: the session changes its id, the next page says who you are, and a host sees their menu")
    void signInAs() throws Exception {
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();

        MvcResult signedIn = mvc.perform(post("/session/user").session(before)
                        .param("userId", String.valueOf(hostId)).param("returnTo", "/bookings"))
                .andExpect(redirectedUrl("/bookings"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session.getId()).as("a new session id against session fixation").isNotEqualTo(oldId);
        assertThat(session.getAttribute(DemoSession.USER_ID)).isEqualTo(hostId);
        assertThat(html(follow(signedIn)))
                .contains("You are now acting as Asha Menon.", "Signed in as Asha Menon", "List a place");
    }

    @Test
    @DisplayName("sign out forgets the user; an unknown user id is refused with a message")
    void signOutAndUnknownUser() throws Exception {
        MockHttpSession session = signIn(guestId);

        MvcResult signedOut = mvc.perform(post("/session/user").session(session)).andExpect(redirectedUrl("/")).andReturn();
        assertThat(session.isInvalid()).isTrue();
        assertThat(html(follow(signedOut))).contains("Signed out.").doesNotContain("Signed in as");

        MvcResult unknown = mvc.perform(post("/session/user").param("userId", "9999")).andExpect(redirectedUrl("/")).andReturn();
        assertThat(html(follow(unknown))).contains("There is no user with id 9999.");
    }

    @Test
    @DisplayName("returnTo only ever leads back into this site: never an open redirect")
    void noOpenRedirect() throws Exception {
        for (String elsewhere : new String[] {"https://evil.example/", "//evil.example/", "/\\evil.example", "evil"}) {
            mvc.perform(post("/session/user").param("userId", String.valueOf(guestId)).param("returnTo", elsewhere))
                    .andExpect(redirectedUrl("/"));
        }
        mvc.perform(post("/session/user").param("userId", String.valueOf(guestId)).param("returnTo", "/?city=Goa"))
                .andExpect(redirectedUrl("/?city=Goa"));
    }

    @Test
    @DisplayName("a listing that does not exist is a 404 page in the reader's language, with the site's navbar")
    void notFound() throws Exception {
        assertThat(html(mvc.perform(get("/listings/9999")), 404))
                .contains("Not found", "There is no listing with id 9999.", "Back to the start", "Sign in as");
        assertThat(html(mvc.perform(get("/listings/9999").param("lang", "hi")), 404))
                .contains("<html lang=\"hi\"", "9999");
    }

    @Test
    @DisplayName("a malformed address is a 400 page with Spring's own error, translated")
    void badRequest() throws Exception {
        assertThat(html(mvc.perform(get("/listings/abc")), 400))
                .contains("The request was not valid", "&quot;abc&quot; is not a valid value for id.");
        assertThat(html(mvc.perform(get("/").param("currency", "XYZ").param("lang", "es")), 400))
                .contains("La solicitud no es válida", "XYZ");
    }

    /** A stay that ended, inserted directly: the booking form rightly refuses dates in the past. */
    private void pastStay(long listingId, long guest, int daysAgo) {
        jdbc.update("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 2, 24000.00, 'INR', 'CONFIRMED')
                """, listingId, guest, LocalDate.now().minusDays(daysAgo), LocalDate.now().minusDays(daysAgo - 2));
    }

    private static ReviewRequest review(int rating) {
        ReviewRequest review = new ReviewRequest();
        review.setRating(rating);
        return review;
    }
}
