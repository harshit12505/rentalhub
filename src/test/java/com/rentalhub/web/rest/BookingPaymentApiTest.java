package com.rentalhub.web.rest;

import com.jayway.jsonpath.JsonPath;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.payment.SimulatedPaymentGateway;
import com.rentalhub.scheduling.PaymentReconciliationJob;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paying for a booking through the API: every way a payment can end, and what each leaves
 * behind. The tests have no Stripe key, so the simulator stands in for Stripe, driven by the
 * payment-method id each request sends (see SimulatedPaymentGateway).
 *
 * The rule under test throughout: no half-made booking. Paid means CONFIRMED; not paid means
 * released, with its dates free at once; not known means PENDING, until the job finds out.
 */
class BookingPaymentApiTest extends IntegrationTest {

    private static final String HEADER = ApiHeaders.DEMO_USER_ID;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private SimulatedPaymentGateway simulator;

    @Autowired
    private PaymentReconciliationJob job;

    private final LocalDate checkIn = LocalDate.now().plusDays(30);
    private long guestId;
    private long otherGuestId;
    private long listingId;

    @BeforeEach
    void createListing() {
        long hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        otherGuestId = users.save(TestRequests.secondGuest()).getId();
        // 2,500.00 INR a night, sleeps 2. Every booking below is 3 nights: ₹7,500.00.
        listingId = propertyService.create(TestRequests.validApartment(), hostId).id();
    }

    @Test
    @DisplayName("paid: 201, CONFIRMED and PAID, and the charge was the listing's own total in its own currency")
    void paid() throws Exception {
        String reference = read(book(guestId, "pm_card_visa")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payment.status").value("PAID"))
                .andExpect(jsonPath("$.payment.provider").value("SIMULATED")), "$.payment.reference");

        SimulatedPaymentGateway.Payment charged = simulator.find(reference).orElseThrow();
        assertThat(charged.amount()).isEqualByComparingTo("7500.00");
        assertThat(charged.currency()).isEqualTo(Currency.INR);
        assertThat(charged.state()).isEqualTo(SimulatedPaymentGateway.State.SUCCEEDED);
    }

    @Test
    @DisplayName("declined: 402, nothing charged, the attempt kept on record, and the dates free at once")
    void declinedCardReleasesTheDates() throws Exception {
        book(guestId, "pm_card_visa_chargeDeclined")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Payment failed"))
                .andExpect(jsonPath("$.messageKey").value("payment.declined"))
                .andExpect(jsonPath("$.detail").value(
                        "The card was declined, and nothing was charged. Please try a different card."));

        mvc.perform(get("/api/bookings").header(HEADER, guestId))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].payment.status").value("FAILED"));
        // The declined payment was cancelled at the provider, so it can never be completed later.
        assertThat(simulator.find(onlyPaymentReference()).orElseThrow().state())
                .isEqualTo(SimulatedPaymentGateway.State.CANCELED);

        book(otherGuestId, "pm_card_visa").andExpect(status().isCreated());
        assertThat(liveBookings()).isEqualTo(1);
    }

    @Test
    @DisplayName("3-D Secure needed: 402 with its own message, and the dates released")
    void authenticationRequired() throws Exception {
        book(guestId, "pm_card_authenticationRequired")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.messageKey").value("payment.authenticationRequired"));

        assertThat(liveBookings()).isZero();
    }

    @Test
    @DisplayName("the provider refuses: 503 with Retry-After, nothing charged, and the dates released")
    void providerDown() throws Exception {
        book(guestId, SimulatedPaymentGateway.PROVIDER_DOWN)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.messageKey").value("payment.unavailable"));

        assertThat(liveBookings()).isZero();
    }

    @Test
    @DisplayName("the answer is lost: 202 and PENDING with the dates held, until the job finds the payment went through")
    void lostAnswerIsSettledByTheJob() throws Exception {
        MvcResult accepted = book(guestId, SimulatedPaymentGateway.NO_ANSWER)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", matchesRegex(".*/api/bookings/\\d+$")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.status").value("UNPAID"))
                .andExpect(jsonPath("$.payment.reference").value(startsWith("sim_pi_")))
                .andReturn();
        long bookingId = idFrom(accepted);

        // The money may have been taken, so the dates stay held...
        book(otherGuestId, "pm_card_visa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("booking.dates.unavailable"));
        // ...and the booking can't be cancelled while its payment is undecided.
        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, guestId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("booking.cancel.paymentPending"));

        assertThat(job.reconcile().confirmed()).as("too young: it might still be under way").isEmpty();
        makeOlder(bookingId);
        assertThat(job.reconcile().confirmed()).containsExactly(bookingId);

        mvc.perform(get("/api/bookings/{id}", bookingId).header(HEADER, guestId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.payment.status").value("PAID"));
    }

    @Test
    @DisplayName("cancelling a paid booking refunds it in full; cancelling again refunds nothing more")
    void cancelRefunds() throws Exception {
        long bookingId = idFrom(book(guestId, "pm_card_visa").andExpect(status().isCreated()).andReturn());

        String refund = read(mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"))
                .andExpect(jsonPath("$.payment.refundReference").value(startsWith("sim_re_"))), "$.payment.refundReference");

        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.refundReference").value(refund));
        assertThat(simulator.find(onlyPaymentReference()).orElseThrow().state())
                .isEqualTo(SimulatedPaymentGateway.State.REFUNDED);
    }

    @Test
    @DisplayName("the booking's audit history records each step of its payment")
    void historyShowsEveryStep() throws Exception {
        long bookingId = idFrom(book(guestId, "pm_card_visa").andReturn());

        List<Map<String, Object>> steps = jdbc.queryForList("""
                SELECT status, payment_status, payment_reference IS NOT NULL AS has_payment
                FROM bookings_aud WHERE id = ? ORDER BY rev
                """, bookingId);

        assertThat(steps).extracting(step -> step.get("status") + "/" + step.get("payment_status") + "/" + step.get("has_payment"))
                .containsExactly("PENDING/UNPAID/false", "PENDING/UNPAID/true", "CONFIRMED/PAID/true");
    }

    @Test
    @DisplayName("a booking made before payments existed can be cancelled, with nothing to refund")
    void bookingFromBeforePayments() throws Exception {
        Long bookingId = jdbc.queryForObject("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 2, 7500.00, 'INR', 'CONFIRMED') RETURNING id
                """, Long.class, listingId, guestId, checkIn, checkIn.plusDays(3));

        mvc.perform(post("/api/bookings/{id}/cancel", bookingId).header(HEADER, guestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("NONE"))
                .andExpect(jsonPath("$.payment.refundReference").doesNotExist());
    }

    private ResultActions book(long userId, String paymentMethodId) throws Exception {
        return mvc.perform(post("/api/bookings").header(HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"propertyId": %d, "checkIn": "%s", "checkOut": "%s", "guests": 2, "paymentMethodId": "%s"}
                        """.formatted(listingId, checkIn, checkIn.plusDays(3), paymentMethodId)));
    }

    /** Moves a booking's creation back an hour, past the job's 10-minute wait. */
    private void makeOlder(long bookingId) {
        jdbc.update("UPDATE bookings SET created_at = created_at - interval '1 hour' WHERE id = ?", bookingId);
    }

    private String onlyPaymentReference() {
        return jdbc.queryForObject("SELECT payment_reference FROM bookings WHERE guest_id = ?", String.class, guestId);
    }

    private long liveBookings() {
        return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE status IN ('PENDING', 'CONFIRMED')", Long.class);
    }

    private static long idFrom(MvcResult result) {
        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private static String read(ResultActions response, String path) throws Exception {
        return JsonPath.read(response.andReturn().getResponse().getContentAsString(), path);
    }
}
