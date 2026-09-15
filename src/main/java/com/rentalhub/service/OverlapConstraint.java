package com.rentalhub.service;

/**
 * Recognises the database refusing a booking because it overlaps another one: an
 * exclusion-constraint violation (SQLState 23P01) of {@value #NAME} (V1).
 */
final class OverlapConstraint {

    static final String NAME = "no_overlapping_bookings";

    private OverlapConstraint() {
    }

    static boolean violatedBy(Throwable failure) {
        return ConstraintViolations.violated(failure, ConstraintViolations.EXCLUSION_VIOLATION, NAME);
    }
}
