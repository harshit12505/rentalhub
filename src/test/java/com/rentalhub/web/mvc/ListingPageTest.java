package com.rentalhub.web.mvc;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * A listing's page and its forms: book, cancel, review, save, and the host's photo upload with no
 * storage configured. Every form is posted the way the browser posts it, and the next page is
 * read the way the browser gets it: through the redirect.
 */
class ListingPageTest extends PageTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    private final LocalDate today = LocalDate.now();
    private long hostId;
    private long guestId;
    private long villaId;

    @BeforeEach
    void createListing() {
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        villaId = properties.create(TestRequests.validVilla(), hostId).id();
    }

    @Test
    @DisplayName("the page lists the type's own fields generically, labelled and valued in the reader's language")
    void typeFields() throws Exception {
        assertThat(html(mvc.perform(get("/listings/{id}", villaId))))
                .contains("Test villa", "Hosted by Asha Menon", "Plot area (m²)", "450", "Private pool", "Yes",
                        "Sign in (top right) to book, save or review this place.");
        assertThat(html(mvc.perform(get("/listings/{id}", villaId).param("lang", "hi"))))
                .contains("प्लॉट का क्षेत्रफल (m²)", "निजी स्विमिंग पूल", "हाँ");
    }

    @Test
    @DisplayName("a guest sees the booking form; the host sees the photo tools and no booking form")
    void whoSeesWhat() throws Exception {
        String asGuest = html(mvc.perform(get("/listings/{id}", villaId).session(signIn(guestId))));
        assertThat(asGuest).contains("id=\"book\"", "type=\"date\"", "value=\"" + today.plusDays(7) + "\"",
                        "Test card that is declined", "Save")
                .doesNotContain("id=\"photos\"");

        String asHost = html(mvc.perform(get("/listings/{id}", villaId).session(signIn(hostId))));
        assertThat(asHost).contains("This is your listing.", "id=\"photos\"", "enctype=\"multipart/form-data\"")
                .doesNotContain("id=\"book\"");
    }

    @Test
    @DisplayName("booking with the test card that succeeds: My bookings shows it confirmed and paid, and it can be cancelled with a refund")
    void bookAndCancel() throws Exception {
        MockHttpSession guest = signIn(guestId);

        MvcResult booked = mvc.perform(book(guest, today.plusDays(20), today.plusDays(22), "pm_card_visa"))
                .andExpect(redirectedUrl("/bookings"))
                .andReturn();

        String bookings = html(follow(booked));
        assertThat(bookings).contains("Booked: 2 night(s), 24,000.00 INR paid.", "Test villa", "Confirmed", "Paid",
                "2 guest(s)", "Cancel booking");
        long bookingId = jdbc.queryForObject("SELECT id FROM bookings", Long.class);
        assertThat(jdbc.queryForObject("""
                SELECT r.changed_by FROM bookings_aud a JOIN revinfo r ON r.rev = a.rev WHERE a.id = ? ORDER BY a.rev LIMIT 1
                """, String.class, bookingId))
                .as("the page's signed-in user is the audited actor, as the header is for the API")
                .isEqualTo("user:" + guestId);

        MvcResult cancelled = mvc.perform(form("/bookings/{id}/cancel", guest, bookingId))
                .andExpect(redirectedUrl("/bookings"))
                .andReturn();
        assertThat(html(follow(cancelled)))
                .contains("Booking cancelled and refunded in full.", "Cancelled", "Refunded")
                .doesNotContain("Cancel booking</button>");
    }

    @Test
    @DisplayName("a refused booking comes back by redirect, with each problem beside its field, in the reader's language")
    void refusedBookingInSpanish() throws Exception {
        MockHttpSession guest = signIn(guestId);
        LocalDate checkIn = today.plusDays(20);

        MvcResult refused = mvc.perform(book(guest, checkIn, checkIn, "pm_card_visa").param("lang", "es"))
                .andExpect(redirectedUrl("/listings/" + villaId + "#book"))
                .andExpect(flash().attributeExists("booking"))
                .andReturn();

        String page = html(follow(refused));
        assertThat(page).contains("<html lang=\"es\"", "La salida debe ser al menos un día después de la entrada.",
                "is-invalid", "value=\"" + checkIn + "\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a declined card: the form says so, nothing is charged, and the dates are free again")
    void declinedCard() throws Exception {
        MockHttpSession guest = signIn(guestId);

        MvcResult declined = mvc.perform(book(guest, today.plusDays(20), today.plusDays(22), "pm_card_visa_chargeDeclined"))
                .andExpect(redirectedUrl("/listings/" + villaId + "#book"))
                .andReturn();

        assertThat(html(follow(declined))).contains("The card was declined, and nothing was charged.");
        mvc.perform(book(guest, today.plusDays(20), today.plusDays(22), "pm_card_visa"))
                .andExpect(redirectedUrl("/bookings"));
    }

    @Test
    @DisplayName("a form posted without signing in changes nothing and says to sign in first")
    void signInFirst() throws Exception {
        MvcResult refused = mvc.perform(book(null, today.plusDays(20), today.plusDays(22), "pm_card_visa"))
                .andExpect(redirectedUrl("/listings/" + villaId))
                .andReturn();

        assertThat(html(follow(refused))).contains("Sign in first");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a guest whose stay has ended can post a review, which then shows on the page; before a stay it is refused")
    void review() throws Exception {
        MockHttpSession guest = signIn(guestId);

        MvcResult tooSoon = mvc.perform(form("/listings/{id}/reviews", guest, villaId).param("rating", "4"))
                .andExpect(redirectedUrl("/listings/" + villaId + "#reviews"))
                .andReturn();
        assertThat(html(follow(tooSoon))).contains("You can review a listing only after a stay there has ended.");

        jdbc.update("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 2, 24000.00, 'INR', 'CONFIRMED')
                """, villaId, guestId, today.minusDays(6), today.minusDays(4));
        MvcResult posted = mvc.perform(form("/listings/{id}/reviews", guest, villaId)
                        .param("rating", "4").param("comment", "Lovely garden, <b>quiet</b> nights."))
                .andExpect(redirectedUrl("/listings/" + villaId + "#reviews"))
                .andReturn();

        assertThat(html(follow(posted))).contains("Thank you. Your review is posted.", "★★★★",
                "Lovely garden, &lt;b&gt;quiet&lt;/b&gt; nights.", "4 (1 review(s))");
    }

    @Test
    @DisplayName("save toggles: the first press saves the listing, the second removes it")
    void favouriteToggle() throws Exception {
        MockHttpSession guest = signIn(guestId);

        MvcResult saved = mvc.perform(form("/listings/{id}/favorite", guest, villaId)).andReturn();
        assertThat(html(follow(saved))).contains("Saved to your favourites.", "aria-pressed=\"true\"");

        MvcResult removed = mvc.perform(form("/listings/{id}/favorite", guest, villaId)).andReturn();
        assertThat(html(follow(removed))).contains("Removed from your favourites.", "aria-pressed=\"false\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites", Integer.class)).isZero();
    }

    @Test
    @DisplayName("with no image storage configured, a host's upload is refused with a clear message on the listing")
    void uploadWithoutStorage() throws Exception {
        MockHttpSession host = signIn(hostId);

        MvcResult refused = mvc.perform(multipart("/listings/{id}/photos", villaId).session(host)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})))
                .andExpect(redirectedUrl("/listings/" + villaId + "#photos"))
                .andReturn();

        assertThat(html(follow(refused)))
                .contains("Photo uploads are switched off, because no image storage is configured on this server.");
    }

    @Test
    @DisplayName("another user's booking cannot be cancelled from the page")
    void cancelSomeoneElses() throws Exception {
        mvc.perform(book(signIn(guestId), today.plusDays(20), today.plusDays(22), "pm_card_visa"))
                .andExpect(redirectedUrl("/bookings"));
        long bookingId = jdbc.queryForObject("SELECT id FROM bookings", Long.class);
        long otherId = users.save(TestRequests.secondGuest()).getId();

        MvcResult refused = mvc.perform(form("/bookings/{id}/cancel", signIn(otherId), bookingId)).andReturn();

        assertThat(html(follow(refused))).contains("alert-danger");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings", String.class)).isEqualTo("CONFIRMED");
    }

    private MockHttpServletRequestBuilder book(MockHttpSession session, LocalDate checkIn, LocalDate checkOut, String card) {
        return form("/listings/{id}/book", session, villaId)
                .param("checkIn", checkIn.toString())
                .param("checkOut", checkOut.toString())
                .param("guests", "2")
                .param("paymentMethodId", card);
    }
}
