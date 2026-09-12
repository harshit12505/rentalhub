package com.rentalhub.domain.model.enums;

public enum BookingStatus {
    /** Created, payment not yet confirmed. Still blocks the dates. */
    PENDING,
    /** Paid and locked in. */
    CONFIRMED,
    /** Released by the guest or the host. Frees the dates. */
    CANCELLED,
    /** Check-out date has passed. */
    COMPLETED
}
