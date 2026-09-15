package com.rentalhub.service;

import java.sql.SQLException;

/**
 * Recognises one particular database constraint in a failure.
 *
 * Postgres reports a violation with a SQLState (23505 unique, 23P01 exclusion, ...) and
 * puts the constraint's name in the message. Both are checked, so a different
 * constraint of the same kind can't be mistaken for the one meant. The search walks
 * down the cause chain because Spring and Hibernate each wrap the driver's SQLException
 * in an exception of their own. Only JDK types are used, so this compiles without the
 * Postgres driver (a runtime-only dependency).
 *
 * https://www.postgresql.org/docs/16/errcodes-appendix.html
 */
final class ConstraintViolations {

    static final String UNIQUE_VIOLATION = "23505";
    static final String EXCLUSION_VIOLATION = "23P01";

    private ConstraintViolations() {
    }

    static boolean violated(Throwable failure, String sqlState, String constraintName) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && sqlState.equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
