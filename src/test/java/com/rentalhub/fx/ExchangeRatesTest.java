package com.rentalhub.fx;

import com.rentalhub.domain.model.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Conversions through the provider's base currency, rounded once. */
class ExchangeRatesTest {

    /** 1 USD = 80 INR = 0.80 EUR = 0.75 GBP; no AED. */
    private final ExchangeRates rates = new ExchangeRates(Map.of(
            Currency.USD, new BigDecimal("1"),
            Currency.INR, new BigDecimal("80"),
            Currency.EUR, new BigDecimal("0.80"),
            Currency.GBP, new BigDecimal("0.75")), Instant.parse("2026-09-15T00:00:00Z"));

    @Test
    @DisplayName("converts through the base: ₹2,500 is $31.25, and €25.00 without going through dollars first")
    void convertsThroughTheBase() {
        assertThat(convert("2500", Currency.INR, Currency.USD)).isEqualByComparingTo("31.25");
        assertThat(convert("2500", Currency.INR, Currency.EUR)).isEqualByComparingTo("25.00");
        assertThat(convert("100", Currency.EUR, Currency.INR)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("rounds exactly once, the way the caller asks: ₹1,000 is £9.375, shown as £9.38")
    void roundsOnce() {
        BigDecimal pounds = new BigDecimal("1000");

        assertThat(rates.convert(pounds, Currency.INR, Currency.GBP, 3, RoundingMode.HALF_EVEN)).hasValueSatisfying(
                exact -> assertThat(exact).isEqualByComparingTo("9.375"));
        assertThat(rates.convert(pounds, Currency.INR, Currency.GBP, 2, RoundingMode.HALF_EVEN)).hasValueSatisfying(
                shown -> assertThat(shown).isEqualByComparingTo("9.38"));
        assertThat(rates.convert(pounds, Currency.INR, Currency.GBP, 2, RoundingMode.DOWN)).hasValueSatisfying(
                ceiling -> assertThat(ceiling).isEqualByComparingTo("9.37"));
    }

    @Test
    @DisplayName("the rate between two currencies: 1 INR buys 0.010000 EUR")
    void rateBetweenTwoCurrencies() {
        assertThat(rates.rate(Currency.INR, Currency.EUR, 6)).hasValueSatisfying(
                rate -> assertThat(rate).isEqualByComparingTo("0.01").hasScaleOf(6));
    }

    @Test
    @DisplayName("the same currency needs no rate; a currency without one can't be converted")
    void missingRates() {
        assertThat(rates.convert(new BigDecimal("367.25"), Currency.AED, Currency.AED, 2, RoundingMode.HALF_EVEN))
                .hasValueSatisfying(same -> assertThat(same).isEqualByComparingTo("367.25"));
        assertThat(rates.convert(BigDecimal.TEN, Currency.INR, Currency.AED, 2, RoundingMode.HALF_EVEN)).isEmpty();
        assertThat(rates.perBaseUnit()).doesNotContainKey(Currency.AED);
    }

    @Test
    @DisplayName("a rate of zero or less is refused: it would make every conversion nonsense")
    void ratesMustBePositive() {
        assertThatThrownBy(() -> new ExchangeRates(Map.of(Currency.INR, BigDecimal.ZERO), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BigDecimal convert(String amount, Currency from, Currency to) {
        return rates.convert(new BigDecimal(amount), from, to, 2, RoundingMode.HALF_EVEN).orElseThrow();
    }
}
