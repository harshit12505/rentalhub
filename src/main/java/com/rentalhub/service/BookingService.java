package com.rentalhub.service;

import com.rentalhub.config.RetryConfig;
import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Bookings: making and paying for them, viewing them, cancelling them.
 *
 * Making one has two stages. First the dates are held: a PENDING booking is inserted.
 * Then it is paid for (PaymentService), which turns it CONFIRMED or releases it.
 *
 * Holding the dates is the hard part, because "check the calendar, then insert" is a
 * race: two guests can both pass the check before either has inserted. Three layers deal
 * with it, from the outside in:
 * <ol>
 *   <li><b>Retry, then recover</b> (here). An attempt that loses a race to another
 *       transaction (a version conflict, or a deadlock that Postgres broke by cancelling
 *       it) is run again in a new transaction, because the booking that beat it may have
 *       been for other dates. If every attempt loses, the recover step answers "those
 *       dates were just taken".</li>
 *   <li><b>A version race</b> (BookingAttempt). Each attempt reads the listing with
 *       OPTIMISTIC_FORCE_INCREMENT, which raises the listing's version on commit. Of two
 *       transactions booking one listing at once, only the first to commit can raise it;
 *       the other fails and rolls back.</li>
 *   <li><b>The database.</b> The exclusion constraint no_overlapping_bookings refuses an
 *       overlapping row whatever the code does. That refusal is not retried, since the
 *       answer could only be the same; it gets the same "just taken" message.</li>
 * </ol>
 * The retry must be outside the transaction. The version check runs while the
 * transaction commits, which is after the transactional method has returned, so a retry
 * inside the transaction would never see it fail. The payment is outside it too, and
 * after it: a network call must never run inside a database transaction.
 */
@Slf4j
@Service
public class BookingService {

    private final BookingAttempt attempt;
    private final RetryTemplate retry;
    private final BookingRules rules;
    private final BookingRepository bookings;
    private final PropertyRepository properties;
    private final BookingUpdates updates;
    private final PaymentService payments;

    BookingService(BookingAttempt attempt,
                   @Qualifier(RetryConfig.BOOKING_RETRY) RetryTemplate retry,
                   BookingRules rules,
                   BookingRepository bookings,
                   PropertyRepository properties,
                   BookingUpdates updates,
                   PaymentService payments) {
        this.attempt = attempt;
        this.retry = retry;
        this.rules = rules;
        this.bookings = bookings;
        this.properties = properties;
        this.updates = updates;
        this.payments = payments;
    }

    /**
     * Books a stay for the guest and pays for it, or throws: InvalidRequestException (400)
     * when a rule refuses it, ResourceNotFoundException (404), OperationNotAllowedException
     * (403) for one's own listing, ConflictException (409) when the listing or the dates are
     * taken, PaymentFailedException (402) or PaymentUnavailableException (503) when the
     * payment fails, in which case the dates have been released again.
     *
     * Deliberately not {@code @Transactional}: each step brings its own transaction.
     *
     * @return the booking, CONFIRMED; or, if the payment provider's answer was lost, PENDING
     *         until the reconciliation job finds out what happened
     */
    public BookingView book(BookingRequest request, long guestId) {
        // Cheap checks first: no transaction and no retry for a request that can never succeed.
        rules.checkDates(request.getCheckIn(), request.getCheckOut());
        BookingView held = hold(request, guestId);
        // An event name plus key/value pairs: "booking.created bookingId=1 ..." locally,
        // separate JSON fields in the render profile's logs.
        log.atInfo().setMessage("booking.created")
                .addKeyValue("bookingId", held.id())
                .addKeyValue("propertyId", held.property().id())
                .addKeyValue("guestId", guestId)
                .addKeyValue("checkIn", held.checkIn())
                .addKeyValue("checkOut", held.checkOut())
                .addKeyValue("total", held.totalAmount())
                .addKeyValue("currency", held.currency())
                .addKeyValue("status", held.status())
                .log();
        return payments.collect(held, request.getPaymentMethodId());
    }

    /** Inserts the booking as PENDING, which holds its dates, surviving races as described above. */
    private BookingView hold(BookingRequest request, long guestId) {
        try {
            // invoke() runs the attempt, runs it again after a lost race (the policy is in
            // RetryConfig) and, if it never succeeds, rethrows the last failure as it was.
            return retry.invoke(() -> attempt.place(request, guestId));
        } catch (ConcurrencyFailureException lostEveryRace) {
            throw recover(request, guestId, lostEveryRace);
        } catch (DataIntegrityViolationException refused) {
            if (!OverlapConstraint.violatedBy(refused)) {
                throw refused;
            }
            // Our check found the dates free, yet the database refused the row: a booking
            // for them was inserted by someone else in the moment between.
            log.atInfo().setMessage("booking.race.lost")
                    .addKeyValue("propertyId", request.getPropertyId())
                    .addKeyValue("guestId", guestId)
                    .addKeyValue("checkIn", request.getCheckIn())
                    .addKeyValue("checkOut", request.getCheckOut())
                    .addKeyValue("reason", "overlap-constraint")
                    .log();
            throw new ConflictException("booking.dates.justTaken");
        }
    }

    /**
     * The recover path: every attempt lost a race on this listing, so others are booking
     * it right now. Give up with an answer the guest can act on.
     */
    private ConflictException recover(BookingRequest request, long guestId, ConcurrencyFailureException last) {
        log.atWarn().setMessage("booking.retry.exhausted")
                .addKeyValue("propertyId", request.getPropertyId())
                .addKeyValue("guestId", guestId)
                .addKeyValue("checkIn", request.getCheckIn())
                .addKeyValue("checkOut", request.getCheckOut())
                .addKeyValue("lastCause", last.getClass().getSimpleName())
                .log();
        return new ConflictException("booking.dates.justTaken");
    }

    @Transactional(readOnly = true)
    public BookingView get(long bookingId, long actingUserId) {
        Booking booking = bookings.findWithDetailsById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("booking.notFound", bookingId));
        rules.checkVisibleTo(booking, actingUserId);
        return BookingViews.toView(booking);
    }

    /** The acting user's own trips, latest check-in first. */
    @Transactional(readOnly = true)
    public List<BookingView> forGuest(long guestId) {
        return bookings.findByGuestIdOrderByCheckInDescIdDesc(guestId).stream()
                .map(BookingViews::toView)
                .toList();
    }

    /** Every booking of one listing, in check-in order. Only its host may see them. */
    @Transactional(readOnly = true)
    public List<BookingView> forListing(long propertyId, long actingUserId) {
        Property property = properties.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
        if (property.getHost().getId() != actingUserId) {
            throw new OperationNotAllowedException("booking.listing.notHost");
        }
        return bookings.findByPropertyIdOrderByCheckInAscIdAsc(propertyId).stream()
                .map(BookingViews::toView)
                .toList();
    }

    /**
     * Cancels a booking, which frees its dates at once, and gives the money back in full if it
     * was paid for (a free cancellation until check-in day).
     *
     * Two steps, deliberately not one transaction: the cancellation commits first, then the
     * refund is asked for, outside any transaction. If the refund fails, the booking stays
     * cancelled with a refund owed (CANCELLED and PAID) and the reconciliation job tries
     * again; cancelling again also retries it. A repeated cancel is never an error.
     */
    public BookingView cancel(long bookingId, long actingUserId) {
        return payments.refundIfOwed(updates.cancel(bookingId, actingUserId));
    }
}
