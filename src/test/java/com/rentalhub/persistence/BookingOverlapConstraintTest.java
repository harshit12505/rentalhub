package com.rentalhub.persistence;

import com.rentalhub.domain.model.User;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.factory.PropertyFactory;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves the database itself refuses double bookings, with no application code in
 * the way: rows are inserted with plain SQL. Phase 3 adds the application-level
 * locking and a two-thread race test on top; this constraint is the backstop that
 * holds even if that code has a bug or someone writes to the table directly.
 */
@Transactional
class BookingOverlapConstraintTest extends IntegrationTest {

    /** PostgreSQL error codes: https://www.postgresql.org/docs/16/errcodes-appendix.html */
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String CHECK_VIOLATION = "23514";

    @Autowired
    private PropertyFactory factory;

    @Autowired
    private PropertyRepository properties;

    @Autowired
    private UserRepository users;

    private long propertyId;
    private long otherPropertyId;
    private long guestId;

    @BeforeEach
    void createPropertiesAndGuest() {
        User host = users.save(TestRequests.host());
        guestId = users.save(TestRequests.guest()).getId();
        propertyId = properties.saveAndFlush(factory.create(TestRequests.validApartment(), host)).getId();
        otherPropertyId = properties.saveAndFlush(factory.create(TestRequests.validStudio(), host)).getId();
    }

    @Test
    @DisplayName("overlapping live bookings for the same property are refused")
    void overlapRefused() {
        book(propertyId, "2026-12-10", "2026-12-15", "CONFIRMED");

        Throwable thrown = catchThrowable(() -> book(propertyId, "2026-12-14", "2026-12-18", "PENDING"));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sqlState(thrown)).isEqualTo(EXCLUSION_VIOLATION);
    }

    @Test
    @DisplayName("one guest's check-out day can be the next guest's check-in day")
    void backToBackAllowed() {
        book(propertyId, "2026-12-10", "2026-12-15", "CONFIRMED");
        book(propertyId, "2026-12-15", "2026-12-18", "CONFIRMED");

        assertThat(bookingCount(propertyId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a cancelled booking frees its dates")
    void cancelledDoesNotBlock() {
        book(propertyId, "2026-12-10", "2026-12-15", "CANCELLED");
        book(propertyId, "2026-12-10", "2026-12-15", "PENDING");

        assertThat(bookingCount(propertyId)).isEqualTo(2);
    }

    @Test
    @DisplayName("the same dates at a different property do not conflict")
    void otherPropertyUnaffected() {
        book(propertyId, "2026-12-10", "2026-12-15", "CONFIRMED");
        book(otherPropertyId, "2026-12-10", "2026-12-15", "CONFIRMED");

        assertThat(bookingCount(otherPropertyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("check-out must be after check-in")
    void zeroNightStayRefused() {
        Throwable thrown = catchThrowable(() -> book(propertyId, "2026-12-10", "2026-12-10", "PENDING"));

        assertThat(sqlState(thrown)).isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a misspelt status is refused rather than silently escaping the overlap rule")
    void unknownStatusRefused() {
        Throwable thrown = catchThrowable(() -> book(propertyId, "2026-12-10", "2026-12-15", "CONFIRMD"));

        assertThat(sqlState(thrown)).isEqualTo(CHECK_VIOLATION);
    }

    private void book(long property, String checkIn, String checkOut, String status) {
        jdbc.update("""
                INSERT INTO bookings (property_id, guest_id, check_in, check_out, guests, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 2, 12500.00, 'INR', ?)
                """, property, guestId, LocalDate.parse(checkIn), LocalDate.parse(checkOut), status);
    }

    private long bookingCount(long property) {
        return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE property_id = ?", Long.class, property);
    }

    /** The error code Postgres itself reported, dug out from under Spring's exception wrapper. */
    private static String sqlState(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
