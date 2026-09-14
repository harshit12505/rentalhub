package com.rentalhub.domain.model.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum BookingStatus {
    /** Created, payment not yet confirmed. Still blocks the dates. */
    PENDING,
    /** Paid and locked in. */
    CONFIRMED,
    /** Released by the guest or the host. Frees the dates. */
    CANCELLED,
    /** Check-out date has passed. */
    COMPLETED;

    /**
     * The statuses that hold a listing's dates. Must stay in step with the WHERE clause
     * of the no_overlapping_bookings constraint in V1, which lists the same two.
     */
    public static final Set<BookingStatus> LIVE = Collections.unmodifiableSet(EnumSet.of(PENDING, CONFIRMED));
}
