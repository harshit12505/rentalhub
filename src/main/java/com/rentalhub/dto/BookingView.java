package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One booking, as the API (and later the pages) show it. Built inside the transaction
 * that loaded the booking; bookings are never cached, since each is read by only two
 * people and must always be current.
 *
 * @param nights      nights charged: check-out day is not one of them
 * @param totalAmount what the guest is charged, in {@code currency} (always the listing's
 *                    own currency), at that currency's number of decimals
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
        BookingStatus status,
        Instant createdAt) {

    public record Listing(long id, String title, String city) {
    }

    public record Guest(long id, String fullName) {
    }
}
