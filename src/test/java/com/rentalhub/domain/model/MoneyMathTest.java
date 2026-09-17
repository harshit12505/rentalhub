package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Why every amount of money in this project is a BigDecimal, never a double.
 *
 * A double stores a number in base 2, and most decimal fractions (0.1, 0.99) have no exact
 * base-2 form, just as 1/3 has no exact decimal form. So each price is stored very slightly
 * wrong, and the error shows as soon as prices are added up, multiplied or converted. Each
 * test shows one way that goes wrong, next to the exact BigDecimal answer the code uses.
 */
class MoneyMathTest {

    private final User guest = new User("Ravi Kumar", "ravi@example.com", UserRole.GUEST);

    @Test
    @DisplayName("adding up a stay night by night drifts with double; BigDecimal is exact")
    void multiNightTotalDriftsWithDouble() {
        double total = 0;
        for (int night = 0; night < 3; night++) {
            total += 2499.99;
        }
        assertThat(total).isEqualTo(7499.969999999999).isNotEqualTo(7499.97);

        BigDecimal exact = BigDecimal.ZERO;
        for (int night = 0; night < 3; night++) {
            exact = exact.add(new BigDecimal("2499.99"));
        }
        assertThat(exact).isEqualByComparingTo("7499.97");
    }

    @Test
    @DisplayName("longer stays drift further: 30 nights at 1,299.90 is not 38,997.00 in double")
    void driftGrowsWithTheStay() {
        double total = 0;
        for (int night = 0; night < 30; night++) {
            total += 1299.90;
        }
        assertThat(total).isEqualTo(38997.00000000002);
        assertThat(new BigDecimal("1299.90").multiply(BigDecimal.valueOf(30))).isEqualByComparingTo("38997.00");
    }

    @Test
    @DisplayName("a booking's total is exact: 3 nights at ₹2,499.99 is ₹7,499.97, with two decimals")
    void bookingTotalIsExact() {
        Apartment listing = new Apartment();
        listing.setPricePerNight(new BigDecimal("2499.99"));
        listing.setCurrency(Currency.INR);

        Booking booking = Booking.reserve(listing, guest, LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 13), 2);

        assertThat(booking.getTotalAmount()).isEqualByComparingTo("7499.97").hasScaleOf(2);
    }

    @Test
    @DisplayName("turning rupees into paise with double loses a paisa; toMinorUnits doesn't")
    void minorUnitsWithDoubleLoseMoney() {
        // The classic payment bug: 2499.99 × 100 is 249998.99999999997 in double, and the cast truncates.
        assertThat((long) (2499.99 * 100)).isEqualTo(249998L);

        assertThat(Currency.INR.toMinorUnits(new BigDecimal("2499.99"))).isEqualTo(249999L);
        assertThat(Currency.INR.toMinorUnits(new BigDecimal("7500.0000"))).as("four decimals from NUMERIC(19,4)")
                .isEqualTo(750000L);
    }

    @Test
    @DisplayName("an amount with a fraction of a paisa is refused, never silently rounded")
    void minorUnitsNeverRound() {
        assertThatThrownBy(() -> Currency.INR.toMinorUnits(new BigDecimal("7500.005")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("new BigDecimal(0.1) inherits the double's error, so money is built from strings")
    void buildMoneyFromStrings() {
        assertThat(new BigDecimal(0.1).toPlainString()).startsWith("0.1000000000000000055511151231257827");
        assertThat(new BigDecimal(0.1)).isNotEqualByComparingTo("0.1");
        assertThat(new BigDecimal("0.1")).isEqualByComparingTo("0.1");
    }

    @Test
    @DisplayName("equals() also compares the number of decimals, so money is compared with compareTo()")
    void compareToNotEquals() {
        BigDecimal fromTheDatabase = new BigDecimal("2500.0000");   // NUMERIC(19,4) gives four decimals
        BigDecimal fromARequest = new BigDecimal("2500.00");

        assertThat(fromTheDatabase.equals(fromARequest)).isFalse();
        assertThat(fromTheDatabase.compareTo(fromARequest)).isZero();
    }

    @Test
    @DisplayName("a division that never ends needs a rounding mode; without one, BigDecimal refuses")
    void divisionNeedsARoundingMode() {
        BigDecimal total = new BigDecimal("100.00");
        BigDecimal three = BigDecimal.valueOf(3);

        assertThatThrownBy(() -> total.divide(three))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("Non-terminating decimal expansion");
        assertThat(total.divide(three, 2, RoundingMode.HALF_EVEN)).isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("half-even rounding sends an exact half to the even neighbour, so errors cancel out")
    void halfEvenRounding() {
        assertThat(Currency.USD.round(new BigDecimal("2.345"))).isEqualByComparingTo("2.34");
        assertThat(Currency.USD.round(new BigDecimal("2.355"))).isEqualByComparingTo("2.36");
        // HALF_UP would give 2.35 and 2.36: every half goes up, so totals creep upwards.
        assertThat(new BigDecimal("2.345").setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("2.35");
    }
}
