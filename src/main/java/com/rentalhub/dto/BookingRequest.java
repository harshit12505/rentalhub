package com.rentalhub.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * A guest asking to stay at a listing. The check-in day is the first night; the
 * check-out day is the morning they leave, and is not charged.
 *
 * Only "is it there, is it the right kind of value" is checked here. Rules that need a
 * clock or the listing (check-in not in the past, no more guests than the listing
 * sleeps) are checked by the booking service, which relies on this validation having
 * run first.
 *
 * A mutable class rather than a record because the booking form (phase 8) binds to it.
 */
@Getter
@Setter
public class BookingRequest {

    @NotNull(message = "{validation.required}")
    private Long propertyId;

    @NotNull(message = "{validation.required}")
    private LocalDate checkIn;

    @NotNull(message = "{validation.required}")
    private LocalDate checkOut;

    @NotNull(message = "{validation.required}")
    @Min(value = 1, message = "{booking.guests.min}")
    private Integer guests;

    /**
     * The card to pay with, as a Stripe PaymentMethod id. In test mode these are Stripe's
     * ready-made test cards, such as {@code pm_card_visa} (succeeds) or
     * {@code pm_card_visa_chargeDeclined} (declined); the simulator answers to the same ids.
     * A real client gets one from Stripe's own card form in the browser, so card numbers
     * never reach this server.
     */
    @NotNull(message = "{validation.required}")
    @Pattern(regexp = "pm_[A-Za-z0-9_]{1,200}", message = "{booking.paymentMethod.format}")
    private String paymentMethodId;
}
