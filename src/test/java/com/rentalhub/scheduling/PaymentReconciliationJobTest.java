package com.rentalhub.scheduling;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.payment.PaymentRequest;
import com.rentalhub.payment.SimulatedPaymentGateway;
import com.rentalhub.service.BookingService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment reconciliation job, run by calling its method directly. Each test sets up one
 * of the moments a normal payment can stop at (a lost answer, a crash between steps, a
 * failed refund) and checks the job finishes it off.
 *
 * Bookings are made to look older than the job's 10-minute wait with plain SQL.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentReconciliationJobTest extends IntegrationTest {

    @Autowired
    private PaymentReconciliationJob job;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private UserRepository users;

    @Autowired
    private SimulatedPaymentGateway simulator;

    private final LocalDate checkIn = LocalDate.now().plusDays(30);
    private long guestId;
    private long listingId;

    @BeforeEach
    void createListing() {
        long hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
        listingId = propertyService.create(TestRequests.validApartment(), hostId).id();
    }

    @Test
    @DisplayName("a lost answer whose payment went through: the booking is confirmed, recorded as the job's doing")
    void confirmsAPaymentThatWentThrough(CapturedOutput output) {
        long id = book(SimulatedPaymentGateway.NO_ANSWER);
        makeOlder(id);

        PaymentReconciliationJob.Report report = job.reconcile();

        assertThat(report.confirmed()).containsExactly(id);
        assertThat(state(id)).isEqualTo("CONFIRMED/PAID");
        assertThat(jdbc.queryForObject("""
                SELECT r.changed_by FROM bookings_aud a JOIN revinfo r ON r.rev = a.rev
                WHERE a.id = ? ORDER BY a.rev DESC LIMIT 1
                """, String.class, id)).isEqualTo("system:" + PaymentReconciliationJob.NAME);
        assertThat(output).contains("job.paymentReconciliation.finished").contains("confirmed=[" + id + "]");
    }

    @Test
    @DisplayName("a payment created but never confirmed (a crash in between): the dates are released, the payment cancelled")
    void releasesAPaymentThatNeverHappened() {
        String reference = simulator.createPayment(new PaymentRequest(0L, new BigDecimal("7500.00"), Currency.INR,
                "test-" + UUID.randomUUID()));
        long id = insertOldPending(reference);

        assertThat(job.reconcile().released()).containsExactly(id);
        assertThat(state(id)).isEqualTo("CANCELLED/FAILED");
        assertThat(simulator.find(reference).orElseThrow().state()).isEqualTo(SimulatedPaymentGateway.State.CANCELED);
    }

    @Test
    @DisplayName("stopped before any payment was recorded: released, without asking the provider anything")
    void releasesWithoutAPayment() {
        long id = insertOldPending(null);

        assertThat(job.reconcile().released()).containsExactly(id);
        assertThat(state(id)).isEqualTo("CANCELLED/FAILED");
    }

    @Test
    @DisplayName("a payment that may still be under way is left alone")
    void leavesRecentPaymentsAlone() {
        long id = book(SimulatedPaymentGateway.NO_ANSWER);

        PaymentReconciliationJob.Report report = job.reconcile();

        assertThat(report.confirmed()).isEmpty();
        assertThat(report.released()).isEmpty();
        assertThat(state(id)).isEqualTo("PENDING/UNPAID");
    }

    @Test
    @DisplayName("a refund that failed is tried again; a second run then finds nothing left to do")
    void retriesARefundOwed() {
        long id = book(TestRequests.PAYS);
        // As if the refund had failed right after the cancellation: cancelled, money still held.
        jdbc.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", id);

        assertThat(job.reconcile().refunded()).containsExactly(id);
        assertThat(state(id)).isEqualTo("CANCELLED/REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refund_reference FROM bookings WHERE id = ?", String.class, id))
                .startsWith("sim_re_");

        PaymentReconciliationJob.Report second = job.reconcile();
        assertThat(second.refunded()).isEmpty();
        assertThat(second.refundsStillOwed()).isEmpty();
        assertThat(second.confirmed()).isEmpty();
        assertThat(second.released()).isEmpty();
    }

    private long book(String paymentMethodId) {
        return bookingService.book(
                TestRequests.booking(listingId, checkIn, checkIn.plusDays(3), 2, paymentMethodId), guestId).id();
    }

    /** A booking left PENDING an hour ago, as if the app had stopped part-way through paying. */
    private long insertOldPending(String paymentReference) {
        return jdbc.queryForObject("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency,
                                      status, payment_status, payment_provider, payment_reference, created_at)
                VALUES (?, ?, ?, ?, 2, 7500.00, 'INR', 'PENDING', 'UNPAID', ?, ?, now() - interval '1 hour')
                RETURNING id
                """, Long.class, listingId, guestId, checkIn, checkIn.plusDays(3),
                paymentReference == null ? null : "SIMULATED", paymentReference);
    }

    private void makeOlder(long bookingId) {
        jdbc.update("UPDATE bookings SET created_at = created_at - interval '1 hour' WHERE id = ?", bookingId);
    }

    private String state(long bookingId) {
        return jdbc.queryForObject("SELECT status || '/' || payment_status FROM bookings WHERE id = ?",
                String.class, bookingId);
    }
}
