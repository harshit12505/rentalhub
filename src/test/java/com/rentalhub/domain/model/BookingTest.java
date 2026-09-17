package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.domain.model.enums.PaymentStatus;
import com.rentalhub.domain.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(booking.getStatus()).as("held until paid").isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
    }

    @Test
    @DisplayName("paid: the payment is recorded first, then the booking is confirmed")
    void paidBookingIsConfirmed() {
        Booking booking = held();

        booking.paymentStarted(PaymentProvider.STRIPE, "pi_123");
        booking.paid();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(booking.getPaymentReference()).isEqualTo("pi_123");
        assertThat(booking.getPaymentProvider()).isEqualTo(PaymentProvider.STRIPE);
    }

    @Test
    @DisplayName("a failed payment cancels the booking, which frees its dates, and keeps the record")
    void failedPaymentCancels() {
        Booking booking = held();

        booking.paymentFailed();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(BookingStatus.LIVE).doesNotContain(booking.getStatus());
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(booking.refundOwed()).as("nothing was taken").isFalse();
    }

    @Test
    @DisplayName("cancelling a paid stay leaves a refund owed until the refund goes through")
    void cancelThenRefund() {
        Booking booking = held();
        booking.paymentStarted(PaymentProvider.SIMULATED, "sim_pi_1");
        booking.paid();

        booking.cancel();
        assertThat(booking.refundOwed()).isTrue();

        booking.refunded("re_1");
        assertThat(booking.refundOwed()).isFalse();
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(booking.getRefundReference()).isEqualTo("re_1");
    }

    @Test
    @DisplayName("steps out of order are refused: a booking is settled once, and only a paid one is refunded")
    void stepsOutOfOrderAreRefused() {
        Booking booking = held();
        booking.paymentFailed();

        assertThatThrownBy(booking::paid).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> booking.refunded("re_1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(booking::cancel).isInstanceOf(IllegalStateException.class);
    }

    private Booking held() {
        return Booking.reserve(listing("2500.00", Currency.INR), guest,
                LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 13), 2);
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
