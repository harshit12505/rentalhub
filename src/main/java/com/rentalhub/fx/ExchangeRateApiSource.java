package com.rentalhub.fx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rentalhub.domain.model.enums.Currency;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Live rates from ExchangeRate-API's free "open access" endpoint (open.er-api.com).
 *
 * Why this provider: no key and no sign-up; about 160 currencies, including all of
 * RentalHub's (INR, USD, EUR, GBP, AED). Frankfurter, the other popular free API, publishes
 * the European Central Bank's rates, and those have no AED.
 *
 * Its terms of use, which the rest of the app keeps to:
 * <ul>
 *   <li>rates change once a day, and asking more than about once an hour gets an HTTP 429,
 *       so CurrencyService keeps them for an hour;</li>
 *   <li>pages that show the rates credit "Rates By Exchange Rate API" (the phase 8 pages).</li>
 * </ul>
 */
public class ExchangeRateApiSource implements ExchangeRateSource {

    private static final String SUCCESS = "success";

    private final RestClient http;
    private final URI url;

    /**
     * @param http a client with short timeouts (see CurrencyConfig): a slow provider must not
     *             hold up a request for long
     * @param url  the endpoint, including the base currency, e.g. .../v6/latest/USD
     */
    public ExchangeRateApiSource(RestClient http, URI url) {
        this.http = http;
        this.url = url;
    }

    @Override
    public ExchangeRates fetchLatest() {
        LatestRates body = http.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(LatestRates.class);
        if (body == null || !SUCCESS.equals(body.result())) {
            throw new IllegalStateException("The exchange-rate provider answered without rates: "
                    + (body == null ? "an empty response" : body.result() + " " + body.errorType()));
        }
        if (body.lastUpdateUnix() == null || body.rates() == null) {
            throw new IllegalStateException("The exchange-rate response has no rates or no update time");
        }

        Map<Currency, BigDecimal> ours = new EnumMap<>(Currency.class);
        for (Currency currency : Currency.values()) {
            BigDecimal rate = body.rates().get(currency.name());
            if (rate != null) {
                ours.put(currency, rate);
            }
        }
        if (ours.isEmpty()) {
            throw new IllegalStateException("The exchange-rate response has no rate for any currency RentalHub uses");
        }
        return new ExchangeRates(ours, Instant.ofEpochSecond(body.lastUpdateUnix()));
    }

    /**
     * The part of the response RentalHub reads. Each rate arrives as a JSON number and is read
     * into a BigDecimal straight from its digits; reading it into a double first would already
     * have rounded it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LatestRates(
            String result,
            @JsonProperty("error-type") String errorType,
            @JsonProperty("time_last_update_unix") Long lastUpdateUnix,
            Map<String, BigDecimal> rates) {
    }
}
