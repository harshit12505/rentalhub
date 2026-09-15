package com.rentalhub.service;

import com.rentalhub.config.RetryConfig;
import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.enums.BookingStatus;
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
 * Bookings: making them, viewing them, cancelling them.
 *
 * Making one is the hard part, because "check the calendar, then insert" is a race: two
 * guests can both pass the check before either has inserted. Three layers deal with it,
 * from the outside in:
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
 * inside the transaction would never see it fail.
 */
@Slf4j
@Service
public class BookingService {

    private final BookingAttempt attempt;
    private final RetryTemplate retry;
    private final BookingRules rules;
    private final BookingRepository bookings;
    private final PropertyRepository properties;

    BookingService(BookingAttempt attempt,
                   @Qualifier(RetryConfig.BOOKING_RETRY) RetryTemplate retry,
                   BookingRules rules,
                   BookingRepository bookings,
                   PropertyRepository properties) {
        this.attempt = attempt;
        this.retry = retry;
        this.rules = rules;
        this.bookings = bookings;
        this.properties = properties;
    }

    /**
     * Books a stay for the guest, or throws: InvalidRequestException (400) when a rule
     * refuses it, ResourceNotFoundException (404), OperationNotAllowedException (403) for
     * one's own listing, ConflictException (409) when the listing or the dates are taken.
     *
     * Deliberately not {@code @Transactional}: each attempt brings its own transaction.
     */
    public BookingView book(BookingRequest request, long guestId) {
        // Cheap checks first: no transaction and no retry for a request that can never succeed.
        rules.checkDates(request.getCheckIn(), request.getCheckOut());
        BookingView booking;
        try {
            // invoke() runs the attempt, runs it again after a lost race (the policy is in
            // RetryConfig) and, if it never succeeds, rethrows the last failure as it was.
            booking = retry.invoke(() -> attempt.place(request, guestId));
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
        // An event name plus key/value pairs: "booking.created bookingId=1 ..." locally,
        // separate JSON fields in the render profile's logs.
        log.atInfo().setMessage("booking.created")
                .addKeyValue("bookingId", booking.id())
                .addKeyValue("propertyId", booking.property().id())
                .addKeyValue("guestId", guestId)
                .addKeyValue("checkIn", booking.checkIn())
                .addKeyValue("checkOut", booking.checkOut())
                .addKeyValue("total", booking.totalAmount())
                .addKeyValue("currency", booking.currency())
                .log();
        return booking;
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
        return BookingViews.toView(loadVisibleTo(bookingId, actingUserId));
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
     * Cancels a booking, which frees its dates at once: the overlap constraint only
     * counts PENDING and CONFIRMED bookings. Cancelling a booking that is already
     * cancelled changes nothing and is not an error, so a client can safely repeat it.
     *
     * The listing is not locked: nothing a cancellation does can break a rule.
     */
    @Transactional
    public BookingView cancel(long bookingId, long actingUserId) {
        Booking booking = loadVisibleTo(bookingId, actingUserId);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return BookingViews.toView(booking);
        }
        rules.checkCancellable(booking);
        booking.setStatus(BookingStatus.CANCELLED);
        // Write now, so a clash with a simultaneous change to this booking (its own version
        // check) surfaces here and becomes a 409, rather than at commit.
        bookings.flush();
        log.atInfo().setMessage("booking.cancelled")
                .addKeyValue("bookingId", bookingId)
                .addKeyValue("propertyId", booking.getProperty().getId())
                .addKeyValue("byUserId", actingUserId)
                .log();
        return BookingViews.toView(booking);
    }

    /** The guest who made a booking and the listing's host may see and cancel it; nobody else. */
    private Booking loadVisibleTo(long bookingId, long actingUserId) {
        Booking booking = bookings.findWithDetailsById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("booking.notFound", bookingId));
        boolean isGuest = booking.getGuest().getId() == actingUserId;
        boolean isHost = booking.getProperty().getHost().getId() == actingUserId;
        if (!isGuest && !isHost) {
            throw new OperationNotAllowedException("booking.notYours");
        }
        return booking;
    }
}
