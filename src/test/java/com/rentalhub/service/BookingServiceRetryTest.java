package com.rentalhub.service;

import com.rentalhub.config.RetryConfig;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PaymentStatus;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.InvalidRequestException;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The retry and recover logic on its own: the transactional attempt is replaced by a
 * mock that fails on cue, and the retry policy is the real one from RetryConfig, with
 * the waits set to zero. BookingConcurrencyTest shows the same paths against Postgres.
 *
 * The payment step is a mock too, which hands the held booking straight back: payments
 * have tests of their own (PaymentServiceTest). What matters here is that it runs once
 * the dates are held, and never for a booking that was refused.
 */
class BookingServiceRetryTest {

    private static final long GUEST = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    // No waiting between attempts (maxDelay must be positive, so 1 ms).
    private final BookingSettings settings = new BookingSettings(90,
            new BookingSettings.Retry(3, Duration.ZERO, 1.0, Duration.ZERO, Duration.ofMillis(1)));
    private final BookingAttempt attempt = mock(BookingAttempt.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final BookingService service = new BookingService(
            attempt,
            RetryConfig.bookingRetryTemplate(settings.retry()),
            new BookingRules(Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC), settings),
            mock(BookingRepository.class),
            mock(PropertyRepository.class),
            mock(BookingUpdates.class),
            payments);

    private final BookingRequest request = TestRequests.booking(1L, TODAY.plusDays(10), TODAY.plusDays(13), 2);
    private final BookingView held = new BookingView(99L,
            new BookingView.Listing(1L, "Test apartment", "Chennai"), new BookingView.Guest(GUEST, "Ravi Kumar"),
            request.getCheckIn(), request.getCheckOut(), 3, 2, new BigDecimal("7500.00"), Currency.INR, null,
            BookingStatus.PENDING, new BookingView.Payment(PaymentStatus.UNPAID, null, null, null), Instant.EPOCH);

    @BeforeEach
    void paymentHandsTheBookingBack() {
        when(payments.collect(any(), anyString())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("an attempt that loses a version race is run again, and the retry can succeed")
    void retriesAfterALostVersionRace() {
        when(attempt.place(request, GUEST))
                .thenThrow(lostVersionRace())
                .thenThrow(lostVersionRace())
                .thenReturn(held);

        assertThat(service.book(request, GUEST)).isEqualTo(held);
        verify(attempt, times(3)).place(request, GUEST);
        verify(payments, times(1)).collect(held, TestRequests.PAYS);
    }

    @Test
    @DisplayName("a deadlock victim is retried too: Postgres cancelled it only because of the other booking")
    void deadlockVictimIsRetried() {
        when(attempt.place(request, GUEST))
                .thenThrow(new CannotAcquireLockException("could not execute statement [ERROR: deadlock detected]"))
                .thenReturn(held);

        assertThat(service.book(request, GUEST)).isEqualTo(held);
        verify(attempt, times(2)).place(request, GUEST);
    }

    @Test
    @DisplayName("when every attempt loses, the recover path answers 'those dates were just taken'")
    void recoversWhenTheRetriesRunOut() {
        when(attempt.place(request, GUEST)).thenThrow(lostVersionRace());

        assertThatThrownBy(() -> service.book(request, GUEST))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo("booking.dates.justTaken"));
        verify(attempt, times(4)).place(request, GUEST);   // the first attempt and 3 retries
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("the database refusing an overlap gets the same answer, without a pointless retry")
    void overlapConstraintIsMappedNotRetried() {
        when(attempt.place(request, GUEST)).thenThrow(new DataIntegrityViolationException("could not execute statement",
                new SQLException("ERROR: conflicting key value violates exclusion constraint \"no_overlapping_bookings\"",
                        "23P01")));

        assertThatThrownBy(() -> service.book(request, GUEST))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo("booking.dates.justTaken"));
        verify(attempt, times(1)).place(request, GUEST);
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("any other database refusal is passed on, not disguised as 'dates taken'")
    void otherIntegrityViolationsPassThrough() {
        DataIntegrityViolationException foreignKey = new DataIntegrityViolationException("could not execute statement",
                new SQLException("ERROR: insert violates foreign key constraint \"bookings_guest_id_fkey\"", "23503"));
        when(attempt.place(request, GUEST)).thenThrow(foreignKey);

        assertThatThrownBy(() -> service.book(request, GUEST)).isSameAs(foreignKey);
        verify(attempt, times(1)).place(request, GUEST);
    }

    @Test
    @DisplayName("an ordinary refusal such as 'already booked' is not retried: it would only fail again")
    void refusalsAreNotRetried() {
        when(attempt.place(request, GUEST)).thenThrow(new ConflictException("booking.dates.unavailable"));

        assertThatThrownBy(() -> service.book(request, GUEST))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo("booking.dates.unavailable"));
        verify(attempt, times(1)).place(request, GUEST);
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("a request the date rules refuse never reaches the database, or the payment provider")
    void dateRulesRunFirst() {
        BookingRequest backwards = TestRequests.booking(1L, TODAY.plusDays(13), TODAY.plusDays(10), 2);

        assertThatThrownBy(() -> service.book(backwards, GUEST)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(attempt, payments);
    }

    private static ObjectOptimisticLockingFailureException lostVersionRace() {
        return new ObjectOptimisticLockingFailureException(Property.class, 1L);
    }
}
