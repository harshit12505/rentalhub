package com.rentalhub.support;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.fx.ExchangeRateSource;
import com.rentalhub.fx.ExchangeRates;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

/**
 * Exchange rates for the integration tests: fixed, round, and no network involved.
 *
 * 1 USD = 80 INR = 0.80 EUR = 0.75 GBP = 3.6725 AED, so ₹2,500 is exactly $31.25 and
 * €100 is exactly ₹10,000. Replaces the live ExchangeRateApiSource (by being {@code @Primary}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedExchangeRates {

    public static final Map<Currency, BigDecimal> PER_USD = Map.of(
            Currency.USD, new BigDecimal("1"),
            Currency.INR, new BigDecimal("80"),
            Currency.EUR, new BigDecimal("0.80"),
            Currency.GBP, new BigDecimal("0.75"),
            Currency.AED, new BigDecimal("3.6725"));

    @Bean
    @Primary
    ExchangeRateSource fixedExchangeRates(Clock clock) {
        return () -> new ExchangeRates(PER_USD, clock.instant());
    }
}
