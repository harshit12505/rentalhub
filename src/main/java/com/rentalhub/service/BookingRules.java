package com.rentalhub.service;

import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.InvalidRequestException;
import com.rentalhub.exception.OperationNotAllowedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The rules a stay must meet, apart from "are the dates free", which only the database
 * can answer reliably (see BookingAttempt).
 *
 * Kept out of the transactional code so they can be unit-tested with a fixed clock and a
 * listing built in memory. "Today" comes from the injected Clock, never LocalDate.now().
 */
@Component
class BookingRules {

    private final Clock clock;
    private final int maxNights;

    BookingRules(Clock clock, BookingSettings settings) {
        this.clock = clock;
        this.maxNights = settings.maxNights();
    }

    /** Rules that need only the request. Checked before any database work. */
    void checkDates(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn.isBefore(today())) {
            throw InvalidRequestException.onField("checkIn", "booking.checkIn.past");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw InvalidRequestException.onField("checkOut", "booking.checkOut.beforeCheckIn");
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > maxNights) {
            throw InvalidRequestException.onField("checkOut", "booking.nights.max", maxNights);
        }
    }

    /** Rules about the listing. Checked inside the booking's transaction, on the listing it read. */
    void checkListing(Property property, int guests, LocalDate checkOut) {
        if (!property.isActive()) {
            throw new ConflictException("booking.property.inactive");
        }
        if (guests > property.getMaxGuests()) {
            throw InvalidRequestException.onField("guests", "booking.guests.tooMany", property.getMaxGuests());
        }
        // The last day the listing is offered is the last possible check-out day.
        LocalDate lastDay = property.getAvailableUntil();
        if (lastDay != null && checkOut.isAfter(lastDay)) {
            throw InvalidRequestException.onField("checkOut", "booking.checkOut.beyondAvailability", lastDay);
        }
    }

    /**
     * A booking can be cancelled until its stay starts: on check-in day, but not after. Not
     * while its payment is still being decided, though: cancelling then would race the
     * payment's own outcome, and the reconciliation job settles it within minutes.
     */
    void checkCancellable(Booking booking) {
        if (booking.getStatus() == BookingStatus.PENDING) {
            throw new ConflictException("booking.cancel.paymentPending");
        }
        if (booking.getStatus() == BookingStatus.COMPLETED || booking.getCheckIn().isBefore(today())) {
            throw new ConflictException("booking.cancel.tooLate");
        }
    }

    /**
     * Whether cancelling would be accepted today: a confirmed stay that hasn't started. What the
     * pages ask before offering a cancel button, so they never offer one the rule above refuses.
     */
    boolean cancellableToday(BookingStatus status, LocalDate checkIn) {
        return status == BookingStatus.CONFIRMED && !checkIn.isBefore(today());
    }

    /** The guest who made a booking and the listing's host may see and cancel it; nobody else. */
    void checkVisibleTo(Booking booking, long actingUserId) {
        boolean isGuest = booking.getGuest().getId() == actingUserId;
        boolean isHost = booking.getProperty().getHost().getId() == actingUserId;
        if (!isGuest && !isHost) {
            throw new OperationNotAllowedException("booking.notYours");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
