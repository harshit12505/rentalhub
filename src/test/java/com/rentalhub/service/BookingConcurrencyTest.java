package com.rentalhub.service;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Double booking, attempted for real: concurrent bookings against real Postgres.
 *
 * Not {@code @Transactional}: every booking must commit, or fail to, exactly as in
 * production, and each thread needs its own transaction anyway.
 *
 * Two kinds of test:
 * <ul>
 *   <li><b>Races</b> fire two bookings at the same instant and check an outcome that must
 *       hold however the two threads happen to interleave.</li>
 *   <li><b>Staged</b> tests remove the luck. A second connection holds a real lock, the
 *       test waits until Postgres reports the booking blocked on it (pg_stat_activity),
 *       and only then lets go, so the booking loses its race at exactly the step under
 *       test, every run.</li>
 * </ul>
 */
class BookingConcurrencyTest extends IntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    private final LocalDate start = LocalDate.now().plusDays(30);
    private long listingId;
    private long firstGuestId;
    private long secondGuestId;

    @BeforeEach
    void createListingAndGuests() {
        long hostId = users.save(TestRequests.host()).getId();
        firstGuestId = users.save(TestRequests.guest()).getId();
        secondGuestId = users.save(TestRequests.secondGuest()).getId();
        // 2,500.00 INR a night, sleeps 2, version 0.
        listingId = propertyService.create(TestRequests.validApartment(), hostId).id();
    }

    // ---------------------------------------------------------------- races

    @RepeatedTest(5)
    @DisplayName("two guests booking overlapping dates at the same instant: exactly one succeeds")
    void overlappingBookingsHaveOneWinner() throws Exception {
        List<Outcome> outcomes = race(
                TestRequests.booking(listingId, start, start.plusDays(5), 2), firstGuestId,
                TestRequests.booking(listingId, start.plusDays(3), start.plusDays(8), 2), secondGuestId);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        Outcome loser = outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst().orElseThrow();
        // "just taken" if the database refused its row; "unavailable" if its ordinary check
        // saw the winner's committed booking, which is what happens when it started late or
        // when Postgres cancelled it to break a deadlock and its retry came after the winner.
        assertThat(loser.failure()).isInstanceOfSatisfying(ConflictException.class, ex ->
                assertThat(ex.getMessageKey()).isIn("booking.dates.justTaken", "booking.dates.unavailable"));
        assertThat(liveBookings()).isEqualTo(1);
    }

    @RepeatedTest(5)
    @DisplayName("two guests booking different dates at the same instant: both succeed, whoever wins the race")
    void separateDatesBothSucceed() throws Exception {
        List<Outcome> outcomes = race(
                TestRequests.booking(listingId, start, start.plusDays(2), 2), firstGuestId,
                TestRequests.booking(listingId, start.plusDays(10), start.plusDays(12), 2), secondGuestId);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(liveBookings()).isEqualTo(2);
        assertThat(listingVersion()).as("each booking raised it exactly once").isEqualTo(2);
    }

    // --------------------------------------------------------------- staged

    @Test
    @DisplayName("a booking that loses the version race to a price change is retried, and charged the new price")
    void lostVersionRaceIsRetriedAgainstTheFreshListing() throws Exception {
        BookingRequest threeNights = TestRequests.booking(listingId, start, start.plusDays(3), 2);

        // The executor is declared first so it closes last: if the test fails midway, the
        // connection closes (and releases its lock) before we wait for the booking thread.
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection host = dataSource.getConnection()) {
            host.setAutoCommit(false);
            // The host is saving a new price. The row is changed, and locked, but not committed.
            update(host, "UPDATE properties SET price_per_night = 3000, version = version + 1 WHERE id = ?");

            // The booking reads the listing as last committed (2,500 a night, version 0) without
            // waiting, inserts its row, then blocks on commit when it tries to raise the version.
            Future<BookingView> booking = executor.submit(() -> bookingService.book(threeNights, firstGuestId));
            awaitAQueryBlockedOnALock();

            // The booking's UPDATE ... WHERE version = 0 now finds version 1: it lost, and
            // rolls back. The retry reads the listing afresh, at the new price.
            host.commit();

            BookingView result = booking.get(10, TimeUnit.SECONDS);
            assertThat(result.totalAmount()).isEqualByComparingTo("9000.00");   // 3 nights × 3,000
        }
        assertThat(liveBookings()).isEqualTo(1);
        assertThat(listingVersion()).as("the host's edit, then the booking").isEqualTo(2);
    }

    @Test
    @DisplayName("an overlap the availability check could not see is refused by the database, as 'just taken'")
    void uncommittedOverlapIsCaughtByTheConstraint() throws Exception {
        BookingRequest request = TestRequests.booking(listingId, start, start.plusDays(3), 2);

        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection otherGuest = dataSource.getConnection()) {
            otherGuest.setAutoCommit(false);
            // Another guest's booking for the same dates, inserted but not committed. Our
            // availability check only sees committed rows, so it will find the dates free.
            insertBooking(otherGuest, secondGuestId, start, start.plusDays(3));

            // Our booking passes the check, then its INSERT blocks: the constraint must know
            // whether the other booking commits before it can accept or refuse ours.
            Future<BookingView> booking = executor.submit(() -> bookingService.book(request, firstGuestId));
            awaitAQueryBlockedOnALock();

            otherGuest.commit();

            Throwable thrown = catchThrowable(() -> booking.get(10, TimeUnit.SECONDS));
            assertThat(thrown).isInstanceOf(ExecutionException.class);
            assertThat(thrown.getCause()).isInstanceOfSatisfying(ConflictException.class, ex ->
                    assertThat(ex.getMessageKey()).isEqualTo("booking.dates.justTaken"));
        }
        assertThat(liveBookings()).as("only the other guest's").isEqualTo(1);
    }

    // -------------------------------------------------------------- helpers

    /** Runs two bookings on two threads, released together by a barrier. */
    private List<Outcome> race(BookingRequest first, long firstGuest,
                               BookingRequest second, long secondGuest) throws Exception {
        CyclicBarrier bothReady = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome> a = executor.submit(() -> book(bothReady, first, firstGuest));
            Future<Outcome> b = executor.submit(() -> book(bothReady, second, secondGuest));
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private Outcome book(CyclicBarrier bothReady, BookingRequest request, long guestId) throws Exception {
        bothReady.await(10, TimeUnit.SECONDS);
        try {
            return new Outcome(bookingService.book(request, guestId), null);
        } catch (RuntimeException e) {
            return new Outcome(null, e);
        }
    }

    private record Outcome(BookingView booking, RuntimeException failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    /** Waits until some query in this database is waiting for a lock another session holds. */
    private void awaitAQueryBlockedOnALock() {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                """, Integer.class) > 0);
    }

    private void update(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listingId);
            statement.executeUpdate();
        }
    }

    private void insertBooking(Connection connection, long guestId, LocalDate checkIn, LocalDate checkOut)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 2, 7500.00, 'INR', 'CONFIRMED')
                """)) {
            insert.setLong(1, listingId);
            insert.setLong(2, guestId);
            insert.setObject(3, checkIn);
            insert.setObject(4, checkOut);
            insert.executeUpdate();
        }
    }

    private long liveBookings() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE property_id = ? AND status IN ('PENDING', 'CONFIRMED')",
                Long.class, listingId);
    }

    private long listingVersion() {
        return jdbc.queryForObject("SELECT version FROM properties WHERE id = ?", Long.class, listingId);
    }
}
