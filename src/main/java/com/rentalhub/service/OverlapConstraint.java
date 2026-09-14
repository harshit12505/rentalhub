package com.rentalhub.service;

import java.sql.SQLException;

/**
 * Recognises the database refusing a booking because it overlaps another one.
 *
 * Postgres reports an exclusion-constraint violation with SQLState 23P01 and puts the
 * constraint's name in the message. Both are checked, so that some other exclusion
 * constraint added later can't be mistaken for this one. The search walks down the
 * cause chain because Spring and Hibernate each wrap the driver's SQLException in an
 * exception of their own. Only JDK types are used, so this compiles without the
 * Postgres driver (a runtime-only dependency).
 */
final class OverlapConstraint {

    static final String NAME = "no_overlapping_bookings";

    /** https://www.postgresql.org/docs/16/errcodes-appendix.html */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private OverlapConstraint() {
    }

    static boolean violatedBy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && EXCLUSION_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(NAME)) {
                return true;
            }
        }
        return false;
    }
}
