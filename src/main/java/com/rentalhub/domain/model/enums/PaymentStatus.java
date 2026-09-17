package com.rentalhub.domain.model.enums;

/**
 * Where a booking's money stands.
 *
 * Kept apart from BookingStatus, which says whether the stay is on. The two move together
 * while a booking is being paid for (PENDING and UNPAID, then CONFIRMED and PAID, or
 * CANCELLED and FAILED) but part later: a cancelled booking stays PAID until its refund
 * has gone through.
 */
public enum PaymentStatus {
    /** No payment was ever involved: bookings made before payments existed (phase 5). */
    NONE,
    /** The payment has not succeeded yet: about to start, in progress, or its outcome unknown. */
    UNPAID,
    /** The money was taken. */
    PAID,
    /** The payment did not go through. Nothing was charged. */
    FAILED,
    /** The money was given back. */
    REFUNDED
}
