package com.rentalhub.service;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.BookingView;
import com.rentalhub.dto.DisplayPrice;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.dto.SearchResultPage;
import com.rentalhub.fx.ExchangeRateSource;
import com.rentalhub.fx.ExchangeRates;
import com.rentalhub.fx.FxSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Showing money in the currency a viewer asks for, and comparing prices across currencies.
 *
 * Everything here is for display or comparison. What a guest is charged, and what is stored,
 * is always the listing's own price in the listing's own currency. A converted figure is
 * worked out per request and is never saved or cached.
 *
 * <b>Rates</b> are kept in memory and refreshed at most once per {@code refresh-after} (an
 * hour), which is within the free provider's limits and far fresher than its once-a-day
 * updates. When a refresh fails, the last good rates stay in use (until they are
 * {@code max-age} old) and the next attempt waits {@code retry-after}, so a provider outage
 * costs one slow request a minute, not a slow response for everyone. Only one thread fetches
 * at a time; the others carry on with the rates already there.
 *
 * May make an HTTP call, so never call it inside a database transaction.
 */
@Slf4j
@Service
public class CurrencyService {

    /** Decimals of a shown rate ("1 INR = 0.010452 USD"). Display only. */
    static final int RATE_SCALE = 6;
    /** Prices are NUMERIC(19,4), so a ceiling with four decimals compares exactly. */
    static final int CEILING_SCALE = 4;

    private final ExchangeRateSource source;
    private final FxSettings settings;
    private final Clock clock;
    private final ReentrantLock fetching = new ReentrantLock();
    private volatile Snapshot snapshot = Snapshot.NONE;

    public CurrencyService(ExchangeRateSource source, FxSettings settings, Clock clock) {
        this.source = source;
        this.settings = settings;
        this.clock = clock;
    }

    /** The currency to read an amount in when a request doesn't say (see FxSettings). */
    public Currency orDefault(Currency currency) {
        return currency != null ? currency : settings.defaultCurrency();
    }

    /** The rates to use now, or empty if there are none recent enough. */
    public Optional<ExchangeRates> currentRates() {
        Instant now = clock.instant();
        Snapshot current = snapshot;
        // tryLock: while one request fetches, the others use what is already there instead of
        // queueing behind it (or all fetching at once).
        if (!now.isBefore(current.nextFetch()) && fetching.tryLock()) {
            try {
                current = snapshot;
                if (!now.isBefore(current.nextFetch())) {
                    current = fetch(current, now);
                    snapshot = current;
                }
            } finally {
                fetching.unlock();
            }
        }
        return usable(current.rates(), now);
    }

    /**
     * An amount shown in another currency, at that currency's number of decimals, rounded half
     * to even like every other amount here. Empty if there is no rate for it. The same currency
     * needs no rate, so it always works.
     */
    public Optional<DisplayPrice> convertForDisplay(BigDecimal amount, Currency from, Currency to) {
        if (from == to) {
            return Optional.of(new DisplayPrice(to.round(amount), to, BigDecimal.ONE, null));
        }
        return currentRates().flatMap(rates -> rates
                .convert(amount, from, to, to.fractionDigits(), RoundingMode.HALF_EVEN)
                .flatMap(converted -> rates.rate(from, to, RATE_SCALE)
                        .map(rate -> new DisplayPrice(converted, to, rate, rates.asOf()))));
    }

    /**
     * A price limit given in one currency, as a ceiling in every listing currency (see
     * PriceCeilings). Each converted ceiling is rounded down, so rounding can never let in a
     * listing that costs more than the limit. Without rates, only the limit's own currency can
     * be compared.
     */
    public PriceCeilings ceilings(BigDecimal maxPrice, Currency currency) {
        Optional<ExchangeRates> rates = currentRates();
        Map<Currency, BigDecimal> ceilings = new EnumMap<>(Currency.class);
        boolean complete = true;
        for (Currency listingCurrency : Currency.values()) {
            Optional<BigDecimal> ceiling = listingCurrency == currency
                    ? Optional.of(maxPrice.setScale(CEILING_SCALE, RoundingMode.DOWN))
                    : rates.flatMap(r -> r.convert(maxPrice, currency, listingCurrency, CEILING_SCALE, RoundingMode.DOWN));
            if (ceiling.isPresent()) {
                ceilings.put(listingCurrency, ceiling.get());
            } else {
                complete = false;
            }
        }
        return new PriceCeilings(ceilings, complete);
    }

    /** The listing with its price also shown in {@code to}; unchanged when {@code to} is null. */
    public PropertyView inCurrency(PropertyView listing, Currency to) {
        if (to == null) {
            return listing;
        }
        return listing.withDisplayPrice(convertForDisplay(listing.pricePerNight(), listing.currency(), to).orElse(null));
    }

    /**
     * Every listing on the page with its price also shown in {@code to}. A listing that can't
     * be converted has no displayPrice, and the page says the rates were unavailable.
     */
    public SearchResultPage inCurrency(SearchResultPage page, Currency to) {
        if (to == null) {
            return page;
        }
        List<PropertySummary> shown = page.content().stream()
                .map(listing -> listing.withDisplayPrice(
                        convertForDisplay(listing.pricePerNight(), listing.currency(), to).orElse(null)))
                .toList();
        boolean someMissing = shown.stream().anyMatch(listing -> listing.displayPrice() == null);
        return page.withContent(shown, page.exchangeRatesUnavailable() || someMissing);
    }

    /** The booking with its total also shown in {@code to}; unchanged when {@code to} is null. */
    public BookingView inCurrency(BookingView booking, Currency to) {
        if (to == null) {
            return booking;
        }
        return booking.withDisplayTotal(convertForDisplay(booking.totalAmount(), booking.currency(), to).orElse(null));
    }

    public List<BookingView> inCurrency(List<BookingView> bookings, Currency to) {
        return to == null ? bookings : bookings.stream().map(booking -> inCurrency(booking, to)).toList();
    }

    private Snapshot fetch(Snapshot current, Instant now) {
        try {
            ExchangeRates fresh = source.fetchLatest();
            log.atInfo().setMessage("fx.rates.loaded")
                    .addKeyValue("asOf", fresh.asOf())
                    .addKeyValue("currencies", fresh.perBaseUnit().keySet())
                    .log();
            return new Snapshot(fresh, now.plus(settings.refreshAfter()));
        } catch (RuntimeException e) {
            log.atWarn().setMessage("fx.rates.unavailable")
                    .addKeyValue("error", e.getMessage())
                    .addKeyValue("usingRatesOf", current.rates() == null ? "none" : current.rates().asOf())
                    .addKeyValue("retryIn", settings.retryAfter())
                    .log();
            return new Snapshot(current.rates(), now.plus(settings.retryAfter()));
        }
    }

    private Optional<ExchangeRates> usable(ExchangeRates rates, Instant now) {
        if (rates == null || rates.asOf().plus(settings.maxAge()).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(rates);
    }

    /**
     * The rates in use and when to ask for new ones.
     *
     * @param rates     the last good set, or null if there has never been one
     * @param nextFetch when to ask the provider again: later after a success, soon after a failure
     */
    private record Snapshot(ExchangeRates rates, Instant nextFetch) {
        static final Snapshot NONE = new Snapshot(null, Instant.EPOCH);
    }
}
