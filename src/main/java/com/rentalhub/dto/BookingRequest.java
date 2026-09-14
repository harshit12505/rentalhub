package com.rentalhub.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
}
