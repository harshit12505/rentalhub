package com.rentalhub.fx;

import com.rentalhub.domain.model.enums.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Currency settings, bound from {@code rentalhub.fx.*} in application.yml. "FX" is finance
 * shorthand for foreign exchange.
 *
 * @param defaultCurrency the currency a price filter is read in when a request doesn't say.
 *                        INR: every listing so far is in India, so searches written before
 *                        prices could be compared across currencies keep their meaning
 * @param ratesUrl        the live rates endpoint (see ExchangeRateApiSource)
 * @param refreshAfter    how long a set of rates is used before asking for a new one. The
 *                        provider updates once a day and allows about one request an hour
 * @param maxAge          rates last updated longer ago than this are too old to show; until
 *                        then, the last good set stands in while the provider is down
 * @param retryAfter      after a failed fetch, how long to wait before trying again, so an
 *                        outage costs one slow request a minute rather than every request
 * @param connectTimeout  how long to wait to connect to the provider
 * @param readTimeout     how long to wait for its answer
 */
@ConfigurationProperties("rentalhub.fx")
public record FxSettings(
        @DefaultValue("INR") Currency defaultCurrency,
        @DefaultValue("https://open.er-api.com/v6/latest/USD") URI ratesUrl,
        @DefaultValue("1h") Duration refreshAfter,
        @DefaultValue("48h") Duration maxAge,
        @DefaultValue("1m") Duration retryAfter,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout) {
}
