package com.rentalhub.service;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.domain.model.enums.PaymentStatus;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.PaymentFailedException;
import com.rentalhub.exception.PaymentUnavailableException;
import com.rentalhub.payment.PaymentGateway;
import com.rentalhub.payment.PaymentGatewayException;
import com.rentalhub.payment.PaymentGateways;
import com.rentalhub.payment.PaymentOutcome;
import com.rentalhub.payment.PaymentRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Taking a booking's money, giving it back, and settling payments whose outcome was lost.
 *
 * Paying for a booking is a <b>saga</b>: a sequence of steps, each committed on its own, where
 * a failure part-way is repaired by a compensating step instead of a rollback. A rollback is
 * not an option, because the payment provider can't take part in our database transaction.
 * <ol>
 *   <li>BookingAttempt inserts the booking as PENDING, UNPAID. Its dates are held: the
 *       overlap constraint counts PENDING bookings.</li>
 *   <li>A payment is created at the provider. No money moves.</li>
 *   <li>Its id is saved on the booking, so every later question has an answer.</li>
 *   <li>The payment is confirmed with the guest's card.</li>
 *   <li>Succeeded: the booking becomes CONFIRMED, PAID. Failed: the compensating step cancels
 *       it (CANCELLED, FAILED), freeing the dates. Undecided: it stays PENDING, and the payment
 *       reconciliation job asks the provider later.</li>
 * </ol>
 * So a booking is never left half made: it is paid for and confirmed, or released with nothing
 * charged, or, for a few minutes at most, waiting on a known payment.
 *
 * Every call to the provider carries an idempotency key built from the booking and the step,
 * so repeating a step (a network retry, the job running twice) never charges or refunds twice.
 */
@Slf4j
@Service
public class PaymentService {

    /** How {@link #settle(long)} left a booking whose payment outcome had been lost. */
    public enum Settlement {
        /** The provider had taken the money: CONFIRMED, PAID. */
        CONFIRMED,
        /** No money was taken: CANCELLED, FAILED, dates free. */
        RELEASED,
        /** The provider doesn't know yet, or couldn't be asked: try again next run. */
        STILL_PENDING,
        /** It was no longer waiting: something else settled it first. */
        ALREADY_SETTLED
    }

    private final PaymentGateways gateways;
    private final BookingUpdates updates;

    PaymentService(PaymentGateways gateways, BookingUpdates updates) {
        this.gateways = gateways;
        this.updates = updates;
    }

    /**
     * Steps 2 to 5 of the saga, for a booking just placed (PENDING, UNPAID).
     *
     * @return the booking, CONFIRMED and PAID; or still PENDING when the provider's answer never came
     * @throws PaymentFailedException      the card was declined or needs 3-D Secure (402); the booking was released
     * @throws PaymentUnavailableException the provider couldn't take the payment (503); the booking was released
     */
    public BookingView collect(BookingView booking, String paymentMethodId) {
        PaymentGateway gateway = gateways.active();
        long bookingId = booking.id();
        String reference;
        try {
            reference = gateway.createPayment(new PaymentRequest(bookingId, booking.totalAmount(),
                    booking.currency(), idempotencyKey(booking, "create")));
        } catch (PaymentGatewayException e) {
            // There is no payment, so nothing can have been charged: release the dates now.
            updates.markPaymentFailed(bookingId);
            log.atWarn().setMessage("payment.notCharged")
                    .addKeyValue("bookingId", bookingId)
                    .addKeyValue("provider", gateway.provider())
                    .addKeyValue("stage", "create")
                    .addKeyValue("detail", e.getMessage())
                    .log();
            throw new PaymentUnavailableException("payment.unavailable");
        }
        // Committed before any money can move: from here on, whatever happens (a crash, a lost
        // answer), the booking says which payment to ask about.
        updates.recordPaymentStarted(bookingId, gateway.provider(), reference);
        log.atInfo().setMessage("payment.started")
                .addKeyValue("bookingId", bookingId)
                .addKeyValue("provider", gateway.provider())
                .addKeyValue("reference", reference)
                .addKeyValue("amount", booking.totalAmount())
                .addKeyValue("currency", booking.currency())
                .log();

        PaymentOutcome outcome = gateway.confirmPayment(reference, paymentMethodId, idempotencyKey(booking, "confirm"));
        logOutcome(bookingId, gateway.provider(), reference, outcome);
        return switch (outcome.status()) {
            case SUCCEEDED -> updates.markPaid(bookingId);
            // Left PENDING with its dates held, because the money may have been taken. The
            // reconciliation job asks the provider once the dust has settled.
            case UNDECIDED -> updates.view(bookingId);
            case DECLINED, AUTHENTICATION_REQUIRED, NOT_CHARGED -> throw release(gateway, bookingId, reference, outcome);
        };
    }

    /**
     * Gives back the money of a booking cancelled after it was paid for (CANCELLED, PAID), and
     * returns other bookings unchanged. Never throws: if the refund fails, the booking stays
     * CANCELLED and PAID, a refund owed, and the reconciliation job tries again.
     */
    public BookingView refundIfOwed(BookingView booking) {
        BookingView.Payment payment = booking.payment();
        if (booking.status() != BookingStatus.CANCELLED || payment.status() != PaymentStatus.PAID) {
            return booking;
        }
        Optional<PaymentGateway> gateway = gateways.forProvider(payment.provider());
        if (gateway.isEmpty()) {
            log.atWarn().setMessage("payment.refund.providerUnavailable")
                    .addKeyValue("bookingId", booking.id())
                    .addKeyValue("provider", payment.provider())
                    .log();
            return booking;
        }
        try {
            String refund = gateway.get().refund(payment.reference(), idempotencyKey(booking, "refund"));
            log.atInfo().setMessage("payment.refunded")
                    .addKeyValue("bookingId", booking.id())
                    .addKeyValue("provider", payment.provider())
                    .addKeyValue("refundReference", refund)
                    .addKeyValue("amount", booking.totalAmount())
                    .addKeyValue("currency", booking.currency())
                    .log();
            return updates.recordRefund(booking.id(), refund);
        } catch (PaymentGatewayException e) {
            log.atWarn().setMessage("payment.refund.failed")
                    .addKeyValue("bookingId", booking.id())
                    .addKeyValue("provider", payment.provider())
                    .addKeyValue("detail", e.getMessage())
                    .log();
            return booking;
        }
    }

    /**
     * Settles a booking still waiting for its payment's outcome, by asking the provider what
     * happened. For the reconciliation job.
     */
    public Settlement settle(long bookingId) {
        BookingView booking = updates.view(bookingId);
        if (booking.status() != BookingStatus.PENDING) {
            return Settlement.ALREADY_SETTLED;
        }
        BookingView.Payment payment = booking.payment();
        if (payment.reference() == null) {
            // Stopped before the payment's id was saved. That comes before confirming it, so no
            // money can have moved.
            return settled(updates.markPaymentFailed(bookingId), "no payment recorded");
        }
        Optional<PaymentGateway> gateway = gateways.forProvider(payment.provider());
        if (gateway.isEmpty()) {
            log.atWarn().setMessage("payment.reconcile.providerUnavailable")
                    .addKeyValue("bookingId", bookingId)
                    .addKeyValue("provider", payment.provider())
                    .log();
            return Settlement.STILL_PENDING;
        }
        PaymentOutcome outcome = gateway.get().lookUp(payment.reference());
        return switch (outcome.status()) {
            case SUCCEEDED -> settled(updates.markPaid(bookingId), outcome.detail());
            case UNDECIDED -> Settlement.STILL_PENDING;
            case DECLINED, AUTHENTICATION_REQUIRED, NOT_CHARGED -> {
                BookingView released = updates.markPaymentFailed(bookingId);
                cancelQuietly(gateway.get(), bookingId, payment.reference());
                yield settled(released, outcome.detail());
            }
        };
    }

    /** Tries a refund still owed again. For the reconciliation job. True if it went through. */
    public boolean retryRefund(long bookingId) {
        return refundIfOwed(updates.view(bookingId)).payment().status() == PaymentStatus.REFUNDED;
    }

    /** Bookings made before {@code cutoff} that are still waiting for their payment's outcome. */
    public List<Long> unsettledPayments(Instant cutoff) {
        return updates.pendingCreatedBefore(cutoff);
    }

    /** Cancelled bookings whose money has not gone back yet. */
    public List<Long> refundsOwed() {
        return updates.refundsOwed();
    }

    /**
     * The idempotency key for one step of one booking's payment, such as
     * "rentalhub-booking-42-1789430551123-confirm". The creation time is in it because ids
     * alone can repeat: wipe a development database and booking 1 exists again, and Stripe
     * would answer the new booking's request with the old booking's payment.
     */
    static String idempotencyKey(BookingView booking, String step) {
        return "rentalhub-booking-" + booking.id() + "-" + booking.createdAt().toEpochMilli() + "-" + step;
    }

    /**
     * The compensating step: frees the booking's dates, then cancels the payment at the
     * provider. Only freeing the dates matters for correctness.
     */
    private RuntimeException release(PaymentGateway gateway, long bookingId, String reference, PaymentOutcome outcome) {
        updates.markPaymentFailed(bookingId);
        cancelQuietly(gateway, bookingId, reference);
        return switch (outcome.status()) {
            case DECLINED -> new PaymentFailedException("payment.declined");
            case AUTHENTICATION_REQUIRED -> new PaymentFailedException("payment.authenticationRequired");
            default -> new PaymentUnavailableException("payment.unavailable");
        };
    }

    /**
     * Cancels a payment that did not succeed, so that it can never be completed later. This is
     * tidying, not a safeguard: only RentalHub could complete it (the details needed to do so
     * never leave this server), and it won't. So a failure is logged, not thrown.
     */
    private void cancelQuietly(PaymentGateway gateway, long bookingId, String reference) {
        try {
            gateway.cancelPayment(reference);
        } catch (PaymentGatewayException e) {
            log.atInfo().setMessage("payment.cancel.failed")
                    .addKeyValue("bookingId", bookingId)
                    .addKeyValue("reference", reference)
                    .addKeyValue("detail", e.getMessage())
                    .log();
        }
    }

    private Settlement settled(BookingView booking, String detail) {
        Settlement settlement = booking.status() == BookingStatus.CONFIRMED ? Settlement.CONFIRMED : Settlement.RELEASED;
        log.atInfo().setMessage("payment.reconciled")
                .addKeyValue("bookingId", booking.id())
                .addKeyValue("settlement", settlement)
                .addKeyValue("detail", detail)
                .log();
        return settlement;
    }

    private void logOutcome(long bookingId, PaymentProvider provider, String reference, PaymentOutcome outcome) {
        LoggingEventBuilder event = switch (outcome.status()) {
            case SUCCEEDED, DECLINED, AUTHENTICATION_REQUIRED -> log.atInfo();
            case UNDECIDED, NOT_CHARGED -> log.atWarn();
        };
        String name = switch (outcome.status()) {
            case SUCCEEDED -> "payment.succeeded";
            case UNDECIDED -> "payment.undecided";
            case DECLINED -> "payment.declined";
            case AUTHENTICATION_REQUIRED -> "payment.authenticationRequired";
            case NOT_CHARGED -> "payment.notCharged";
        };
        event.setMessage(name)
                .addKeyValue("bookingId", bookingId)
                .addKeyValue("provider", provider)
                .addKeyValue("reference", reference)
                .addKeyValue("detail", outcome.detail())
                .log();
    }
}
