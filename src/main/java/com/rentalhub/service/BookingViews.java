package com.rentalhub.service;

import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.dto.BookingView;

/**
 * Turns a booking into the record the API returns. The caller must have loaded the
 * booking's listing and guest, because there is no database session left afterwards.
 */
final class BookingViews {

    private BookingViews() {
    }

    static BookingView toView(Booking booking) {
        Property property = booking.getProperty();
        User guest = booking.getGuest();
        return new BookingView(
                booking.getId(),
                new BookingView.Listing(property.getId(), property.getTitle(), property.getCity()),
                new BookingView.Guest(guest.getId(), guest.getFullName()),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.nights(),
                booking.getGuests(),
                // Read back from NUMERIC(19,4) it has four decimals; show the currency's own.
                booking.getCurrency().round(booking.getTotalAmount()),
                booking.getCurrency(),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}
