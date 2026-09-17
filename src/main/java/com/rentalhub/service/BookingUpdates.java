package com.rentalhub.service;

import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The changes a booking goes through after it is placed: its payment's progress, its
 * cancellation, its refund. Each one is a short transaction of its own.
 *
 * They live apart from BookingService and PaymentService because those two talk to the
 * payment provider, and a network call must never happen inside a database transaction: it
 * would hold a connection, and any row locks, for as long as the provider takes, and a
 * rollback can't take back a charge anyway. So the pattern is always: commit a step here,
 * call the provider outside any transaction, commit the next step here. Being a separate
 * bean also means every call goes through Spring's transactional proxy (see BookingAttempt).
 */
@Slf4j
@Component
class BookingUpdates {

    private final BookingRepository bookings;
    private final BookingRules rules;

    BookingUpdates(BookingRepository bookings, BookingRules rules) {
        this.bookings = bookings;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public BookingView view(long bookingId) {
        return BookingViews.toView(load(bookingId));
    }

    /** Records which payment is this booking's, before any money moves. */
    @Transactional
    public void recordPaymentStarted(long bookingId, PaymentProvider provider, String reference) {
        load(bookingId).paymentStarted(provider, reference);
    }

    /** The payment succeeded: the booking is CONFIRMED and PAID. */
    @Transactional
    public BookingView markPaid(long bookingId) {
        Booking booking = load(bookingId);
        if (stillPending(booking)) {
            booking.paid();
            bookings.flush();
        }
        return BookingViews.toView(booking);
    }

    /** The payment failed: the booking is CANCELLED, its payment FAILED, and its dates free. */
    @Transactional
    public BookingView markPaymentFailed(long bookingId) {
        Booking booking = load(bookingId);
        if (stillPending(booking)) {
            booking.paymentFailed();
            bookings.flush();
        }
        return BookingViews.toView(booking);
    }

    /**
     * Cancels a booking, for its guest or the listing's host. The dates are free at once.
     * Cancelling a cancelled booking changes nothing and is not an error, so a client can
     * safely repeat it. The listing is not locked: nothing a cancellation does can break a rule.
     */
    @Transactional
    public BookingView cancel(long bookingId, long actingUserId) {
        Booking booking = load(bookingId);
        rules.checkVisibleTo(booking, actingUserId);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return BookingViews.toView(booking);
        }
        rules.checkCancellable(booking);
        booking.cancel();
        // Write now, so a clash with a simultaneous change to this booking (its own version
        // check) surfaces here and becomes a 409, rather than at commit.
        bookings.flush();
        log.atInfo().setMessage("booking.cancelled")
                .addKeyValue("bookingId", bookingId)
                .addKeyValue("propertyId", booking.getProperty().getId())
                .addKeyValue("byUserId", actingUserId)
                .addKeyValue("refundOwed", booking.refundOwed())
                .log();
        return BookingViews.toView(booking);
    }

    /** The money went back: the booking is REFUNDED. Does nothing unless a refund was owed. */
    @Transactional
    public BookingView recordRefund(long bookingId, String refundReference) {
        Booking booking = load(bookingId);
        if (booking.refundOwed()) {
            booking.refunded(refundReference);
            bookings.flush();
        }
        return BookingViews.toView(booking);
    }

    @Transactional(readOnly = true)
    public List<Long> pendingCreatedBefore(Instant cutoff) {
        return bookings.findPendingIdsCreatedBefore(cutoff);
    }

    @Transactional(readOnly = true)
    public List<Long> refundsOwed() {
        return bookings.findRefundOwedIds();
    }

    /**
     * A payment is settled once. If the booking has moved on (the reconciliation job and a
     * late answer both settling it), the second one changes nothing.
     */
    private boolean stillPending(Booking booking) {
        if (booking.getStatus() == BookingStatus.PENDING) {
            return true;
        }
        log.atInfo().setMessage("payment.alreadySettled")
                .addKeyValue("bookingId", booking.getId())
                .addKeyValue("status", booking.getStatus())
                .addKeyValue("paymentStatus", booking.getPaymentStatus())
                .log();
        return false;
    }

    private Booking load(long bookingId) {
        return bookings.findWithDetailsById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("booking.notFound", bookingId));
    }
}
