package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.domain.model.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One booking, as the API (and later the pages) show it. Built inside the transaction
 * that loaded the booking; bookings are never cached, since each is read by only two
 * people and must always be current.
 *
 * @param nights       nights charged: check-out day is not one of them
 * @param totalAmount  what the guest is charged, in {@code currency} (always the listing's
 *                     own currency), at that currency's number of decimals
 * @param displayTotal the total in the currency the viewer asked for, or null. Display only:
 *                     worked out per request, never stored
 * @param payment      where the booking's money stands
 */
public record BookingView(
        long id,
        Listing property,
        Guest guest,
        LocalDate checkIn,
        LocalDate checkOut,
        long nights,
        int guests,
        BigDecimal totalAmount,
        Currency currency,
        DisplayPrice displayTotal,
        BookingStatus status,
        Payment payment,
        Instant createdAt) {

    /** A copy with the total shown in another currency too. */
    public BookingView withDisplayTotal(DisplayPrice displayTotal) {
        return new BookingView(id, property, guest, checkIn, checkOut, nights, guests, totalAmount, currency,
                displayTotal, status, payment, createdAt);
    }

    public record Listing(long id, String title, String city) {
    }

    public record Guest(long id, String fullName) {
    }

    /**
     * @param status          NONE for bookings made before payments existed; then UNPAID, PAID,
     *                        FAILED or REFUNDED
     * @param provider        who holds the money: STRIPE, or SIMULATED when no Stripe key is set
     * @param reference       the provider's id for the payment (Stripe: pi_...)
     * @param refundReference the provider's id for the refund, once there is one
     */
    public record Payment(PaymentStatus status, PaymentProvider provider, String reference, String refundReference) {
    }
}
