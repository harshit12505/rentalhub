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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The bookings API as a client sees it. Not {@code @Transactional}, for the same reason
 * as PropertyApiTest: every request must run in its own transactions, as in production.
 */
class BookingApiTest extends IntegrationTest {

    private static final String HEADER = ApiHeaders.DEMO_USER_ID;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService propertyService;

    private final LocalDate checkIn = LocalDate.now().plusDays(30);
    private long hostId;
    private long guestId;
    private long otherGuestId;
    private long listingId;

    @BeforeEach
    void createListing() {
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        otherGuestId = users.save(TestRequests.secondGuest()).getId();
        // 2,500.00 INR a night, sleeps 2.
        listingId = propertyService.create(TestRequests.validApartment(), hostId).id();
    }

    @Test
    @DisplayName("POST books a stay: 201, a Location header, and the total in the listing's currency")
    void bookAStay() throws Exception {
        book(guestId, listingId, checkIn, checkIn.plusDays(3), 2)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesRegex(".*/api/bookings/\\d+$")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.nights").value(3))
                .andExpect(jsonPath("$.totalAmount").value(7500.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.property.id").value(listingId))
                .andExpect(jsonPath("$.guest.fullName").value("Ravi Kumar"))
                // No Stripe key in the tests: the simulator took the (pretend) payment.
                .andExpect(jsonPath("$.payment.status").value("PAID"))
                .andExpect(jsonPath("$.payment.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.payment.reference").value(startsWith("sim_pi_")))
                .andExpect(jsonPath("$.displayTotal").doesNotExist());
    }

    @Test
    @DisplayName("missing and invalid fields are a 400 listing every bad field")
    void beanValidation() throws Exception {
        mvc.perform(post("/api/bookings").header(HEADER, guestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"propertyId\": " + listingId + ", \"guests\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[0].field").value("checkIn"))
                .andExpect(jsonPath("$.errors[1].field").value("checkOut"))
                .andExpect(jsonPath("$.errors[2].field").value("guests"))
                .andExpect(jsonPath("$.errors[2].message").value("At least one guest must stay."))
                .andExpect(jsonPath("$.errors[3].field").value("paymentMethodId"));
    }

    @Test
    @DisplayName("a payment method that isn't a payment-method id is refused before anything else happens")
    void paymentMethodFormat() throws Exception {
        book(guestId, listingId, checkIn, checkIn.plusDays(3), 2, "4242 4242 4242 4242")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("paymentMethodId"))
                .andExpect(jsonPath("$.errors[0].message").value("Give a payment method id, such as pm_card_visa."));
    }

    @Test
    @DisplayName("a broken date or guest rule is a 400 naming the field")
    void ruleViolations() throws Exception {
        book(guestId, listingId, checkIn.plusDays(3), checkIn, 2)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("checkOut"))
                .andExpect(jsonPath("$.messageKey").value("booking.checkOut.beforeCheckIn"));

        book(guestId, listingId, LocalDate.now().minusDays(1), checkIn, 2)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("checkIn"))
                .andExpect(jsonPath("$.messageKey").value("booking.checkIn.past"));

        book(guestId, listingId, checkIn, checkIn.plusDays(2), 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("guests"))
                .andExpect(jsonPath("$.detail").value("This listing sleeps at most 2 guests."));
    }

    @Test
    @DisplayName("nobody can book their own listing, or one that does not exist")
    void whoMayBookWhat() throws Exception {
        book(hostId, listingId, checkIn, checkIn.plusDays(2), 2)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("booking.ownListing"));

        book(guestId, 9999, checkIn, checkIn.plusDays(2), 2)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messageKey").value("property.notFound"));
    }

    @Test
    @DisplayName("dates already booked are a 409; a stay starting on another's check-out day is fine")
    void overlapAndBackToBack() throws Exception {
        book(guestId, listingId, checkIn, checkIn.plusDays(3), 2).andExpect(status().isCreated());

        book(otherGuestId, listingId, checkIn.plusDays(2), checkIn.plusDays(4), 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("booking.dates.unavailable"))
                .andExpect(jsonPath("$.detail").value("Those dates are already booked. Please choose different dates."));

        book(otherGuestId, listingId, checkIn.plusDays(3), checkIn.plusDays(5), 2).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a deactivated listing takes no bookings")
    void inactiveListing() throws Exception {
        jdbc.update("UPDATE properties SET active = false WHERE id = ?", listingId);

        book(guestId, listingId, checkIn, checkIn.plusDays(2), 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("booking.property.inactive"));
    }

    @Test
    @DisplayName("the guest and the listing's host can see a booking; nobody else can")
    void whoMaySeeABooking() throws Exception {
        long bookingId = createBooking(guestId, checkIn, checkIn.plusDays(2));

        mvc.perform(get("/api/bookings/{id}", bookingId).header(HEADER, guestId)).andExpect(status().isOk());
        mvc.perform(get("/api/bookings/{id}", bookingId).header(HEADER, hostId)).andExpect(status().isOk());
        mvc.perform(get("/api/bookings/{id}", bookingId).header(HEADER, otherGuestId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("booking.notYours"));
        mvc.perform(get("/api/bookings/{id}", 9999).header(HEADER, guestId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("There is no booking with id 9999."));
    }

    @Test
    @DisplayName("GET /api/bookings lists the acting user's own trips, latest check-in first")
    void myTrips() throws Exception {
        createBooking(guestId, checkIn, checkIn.plusDays(2));
        createBooking(guestId, checkIn.plusDays(10), checkIn.plusDays(12));

        mvc.perform(get("/api/bookings").header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].checkIn").value(checkIn.plusDays(10).toString()));
        mvc.perform(get("/api/bookings").header(HEADER, otherGuestId))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("the host can list a listing's bookings; a guest cannot")
    void hostSeesTheListingsBookings() throws Exception {
        createBooking(guestId, checkIn, checkIn.plusDays(2));

        mvc.perform(get("/api/properties/{id}/bookings", listingId).header(HEADER, hostId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].guest.fullName").value("Ravi Kumar"));
        mvc.perform(get("/api/properties/{id}/bookings", listingId).header(HEADER, guestId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.messageKey").value("booking.listing.notHost"));
    }

    @Test
    @DisplayName("cancelling frees the dates at once, and cancelling twice is harmless")
    void cancel() throws Exception {
        long bookingId = createBooking(guestId, checkIn, checkIn.plusDays(3));

        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, otherGuestId))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        book(otherGuestId, listingId, checkIn, checkIn.plusDays(3), 2).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a stay that has already started can no longer be cancelled")
    void cancelTooLate() throws Exception {
        long bookingId = createBooking(guestId, checkIn, checkIn.plusDays(3));
        jdbc.update("UPDATE bookings SET check_in = ?, check_out = ? WHERE id = ?",
                LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), bookingId);

        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, hostId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("booking.cancel.tooLate"));
    }

    @Test
    @DisplayName("booking raises the listing's version, and the cached listing shows the new one")
    void bookingEvictsTheCachedListing() throws Exception {
        mvc.perform(get("/api/properties/{id}", listingId))    // now cached, at version 0
                .andExpect(jsonPath("$.version").value(0));

        createBooking(guestId, checkIn, checkIn.plusDays(2));

        mvc.perform(get("/api/properties/{id}", listingId))
                .andExpect(jsonPath("$.version").value(1));
    }

    private ResultActions book(long userId, long propertyId, LocalDate from, LocalDate to, int guests)
            throws Exception {
        return book(userId, propertyId, from, to, guests, TestRequests.PAYS);
    }

    private ResultActions book(long userId, long propertyId, LocalDate from, LocalDate to, int guests,
                               String paymentMethodId) throws Exception {
        String json = """
                {"propertyId": %d, "checkIn": "%s", "checkOut": "%s", "guests": %d, "paymentMethodId": "%s"}
                """.formatted(propertyId, from, to, guests, paymentMethodId);
        return mvc.perform(post("/api/bookings").header(HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long createBooking(long userId, LocalDate from, LocalDate to) throws Exception {
        String location = book(userId, listingId, from, to, 2)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
