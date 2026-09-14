package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BookingTest {

    private final User guest = new User("Ravi Kumar", "ravi@example.com", UserRole.GUEST);

    @Test
    @DisplayName("the total is the nightly price times the nights, in the listing's own currency")
    void totalIsPriceTimesNights() {
        Booking booking = Booking.reserve(listing("2500.00", Currency.INR), guest,
                LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 13), 2);

        assertThat(booking.nights()).isEqualTo(3);
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("7500.00");
        assertThat(booking.getCurrency()).isEqualTo(Currency.INR);
        assertThat(booking.getStatus()).as("the service decides when to confirm").isEqualTo(BookingStatus.PENDING);
    }

    @Test
    @DisplayName("a price read back with four decimals still gives a total with the currency's two")
    void totalHasTheCurrencysDecimals() {
        // NUMERIC(19,4) hands prices back as 1999.9900.
        Booking booking = Booking.reserve(listing("1999.9900", Currency.USD), guest,
                LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 17), 1);

        assertThat(booking.getTotalAmount()).isEqualByComparingTo("13999.93");
        assertThat(booking.getTotalAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("the check-out day is not charged: arriving on the 10th and leaving on the 11th is one night")
    void checkOutDayIsNotCharged() {
        Booking booking = Booking.reserve(listing("4000.00", Currency.INR), guest,
                LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 11), 1);

        assertThat(booking.nights()).isEqualTo(1);
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("4000.00");
    }

    private static Property listing(String pricePerNight, Currency currency) {
        Apartment listing = new Apartment();
        listing.setPricePerNight(new BigDecimal(pricePerNight));
        listing.setCurrency(currency);
        return listing;
    }
}
