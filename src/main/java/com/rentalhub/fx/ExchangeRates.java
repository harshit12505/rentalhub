package com.rentalhub.fx;

import com.rentalhub.domain.model.enums.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One set of exchange rates as a provider publishes them: how many units of each currency
 * one unit of the provider's base currency (usually USD) buys.
 *
 * A conversion between two currencies goes through the base: amount × rate(to) ÷ rate(from).
 * The multiplication is exact, so the division is the only rounding step, and the result is
 * rounded exactly once, to the scale and rounding mode the caller asks for. Converting to
 * USD first and then onwards would round twice.
 *
 * @param perBaseUnit units of each currency per unit of the base; only currencies RentalHub
 *                    knows, and only those the provider had
 * @param asOf        when the provider last updated these rates
 */
public record ExchangeRates(Map<Currency, BigDecimal> perBaseUnit, Instant asOf) {

    public ExchangeRates {
        Objects.requireNonNull(asOf, "asOf");
        EnumMap<Currency, BigDecimal> copy = new EnumMap<>(Currency.class);
        perBaseUnit.forEach((currency, rate) -> {
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("The rate for " + currency + " must be positive, but is " + rate);
            }
            copy.put(currency, rate);
        });
        perBaseUnit = Collections.unmodifiableMap(copy);
    }

    /**
     * The amount in another currency, at {@code scale} decimals, or empty if either
     * currency has no rate. The same currency needs no rate at all.
     */
    public Optional<BigDecimal> convert(BigDecimal amount, Currency from, Currency to, int scale, RoundingMode rounding) {
        if (from == to) {
            return Optional.of(amount.setScale(scale, rounding));
        }
        BigDecimal fromRate = perBaseUnit.get(from);
        BigDecimal toRate = perBaseUnit.get(to);
        if (fromRate == null || toRate == null) {
            return Optional.empty();
        }
        return Optional.of(amount.multiply(toRate).divide(fromRate, scale, rounding));
    }

    /** How many units of {@code to} one unit of {@code from} buys, at {@code scale} decimals. */
    public Optional<BigDecimal> rate(Currency from, Currency to, int scale) {
        return convert(BigDecimal.ONE, from, to, scale, RoundingMode.HALF_EVEN);
    }
}
