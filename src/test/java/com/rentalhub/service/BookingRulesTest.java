package com.rentalhub.service;

import com.rentalhub.domain.model.Apartment;
import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.InvalidRequestException;
import com.rentalhub.support.TestMessages;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The booking rules, with "today" pinned to 14 Sep 2026 by a fixed clock. */
class BookingRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    private final BookingRules rules = new BookingRules(
            Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC),
            new BookingSettings(90, new BookingSettings.Retry(3, Duration.ZERO, 1.0, Duration.ZERO, Duration.ZERO)));

    // ---------------------------------------------------------------- dates

    @Test
    @DisplayName("check-in may be today, but not in the past")
    void checkInNotInThePast() {
        assertThatNoException().isThrownBy(() -> rules.checkDates(TODAY, TODAY.plusDays(1)));

        assertRefused(() -> rules.checkDates(TODAY.minusDays(1), TODAY.plusDays(1)),
                "checkIn", "booking.checkIn.past");
    }

    @Test
    @DisplayName("check-out must be at least one day after check-in")
    void checkOutAfterCheckIn() {
        assertRefused(() -> rules.checkDates(TODAY.plusDays(5), TODAY.plusDays(5)),
                "checkOut", "booking.checkOut.beforeCheckIn");
        assertRefused(() -> rules.checkDates(TODAY.plusDays(5), TODAY.plusDays(4)),
                "checkOut", "booking.checkOut.beforeCheckIn");
    }

    @Test
    @DisplayName("a stay may be up to the configured maximum number of nights")
    void maximumNights() {
        assertThatNoException().isThrownBy(() -> rules.checkDates(TODAY, TODAY.plusDays(90)));

        InvalidRequestException tooLong = assertRefused(() -> rules.checkDates(TODAY, TODAY.plusDays(91)),
                "checkOut", "booking.nights.max");
        assertThat(TestMessages.english(tooLong)).isEqualTo("A single booking can be at most 90 nights.");
    }

    // -------------------------------------------------------------- listing

    @Test
    @DisplayName("a deactivated listing takes no bookings")
    void listingMustBeActive() {
        Apartment listing = listing(4);
        listing.setActive(false);

        assertThatThrownBy(() -> rules.checkListing(listing, 2, TODAY.plusDays(3)))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo("booking.property.inactive"));
    }

    @Test
    @DisplayName("no more guests than the listing sleeps")
    void guestsWithinTheListingsLimit() {
        assertThatNoException().isThrownBy(() -> rules.checkListing(listing(4), 4, TODAY.plusDays(3)));

        InvalidRequestException tooMany = assertRefused(() -> rules.checkListing(listing(4), 5, TODAY.plusDays(3)),
                "guests", "booking.guests.tooMany");
        assertThat(TestMessages.english(tooMany)).isEqualTo("This listing sleeps at most 4 guests.");
    }

    @Test
    @DisplayName("the stay must end by the listing's last available day")
    void withinAvailability() {
        Apartment listing = listing(4);
        listing.setAvailableUntil(TODAY.plusDays(10));

        assertThatNoException().isThrownBy(() -> rules.checkListing(listing, 2, TODAY.plusDays(10)));
        InvalidRequestException tooLate = assertRefused(() -> rules.checkListing(listing, 2, TODAY.plusDays(11)),
                "checkOut", "booking.checkOut.beyondAvailability");
        assertThat(TestMessages.english(tooLate)).isEqualTo("This listing can be booked only until 2026-09-24.");
    }

    @Test
    @DisplayName("a listing with no end date can be booked any time")
    void noAvailabilityLimit() {
        assertThatNoException().isThrownBy(() -> rules.checkListing(listing(4), 2, TODAY.plusDays(365)));
    }

    // ---------------------------------------------------------- cancelling

    @Test
    @DisplayName("a booking can be cancelled up to and including its check-in day")
    void cancellableUntilCheckIn() {
        assertThatNoException().isThrownBy(() -> rules.checkCancellable(booking(TODAY, BookingStatus.CONFIRMED)));

        assertThatThrownBy(() -> rules.checkCancellable(booking(TODAY.minusDays(1), BookingStatus.CONFIRMED)))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo("booking.cancel.tooLate"));
    }

    @Test
    @DisplayName("a completed stay cannot be cancelled")
    void completedNotCancellable() {
        assertThatThrownBy(() -> rules.checkCancellable(booking(TODAY.plusDays(5), BookingStatus.COMPLETED)))
                .isInstanceOf(ConflictException.class);
    }

    // -------------------------------------------------------------- helpers

    /** Asserts the refusal names the right field and key, and that the key has English text. */
    private static InvalidRequestException assertRefused(ThrowingCallable call, String field, String key) {
        InvalidRequestException[] caught = new InvalidRequestException[1];
        assertThatThrownBy(call).isInstanceOfSatisfying(InvalidRequestException.class, ex -> {
            assertThat(ex.getField()).isEqualTo(field);
            assertThat(ex.getMessageKey()).isEqualTo(key);
            assertThat(TestMessages.english(ex)).isNotBlank();
            caught[0] = ex;
        });
        return caught[0];
    }

    private static Apartment listing(int maxGuests) {
        Apartment listing = new Apartment();
        listing.setMaxGuests(maxGuests);
        return listing;
    }

    private static Booking booking(LocalDate checkIn, BookingStatus status) {
        Booking booking = new Booking();
        booking.setCheckIn(checkIn);
        booking.setCheckOut(checkIn.plusDays(2));
        booking.setStatus(status);
        return booking;
    }
}
