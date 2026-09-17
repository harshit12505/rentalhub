package com.rentalhub.service;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.DisplayPrice;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.SearchResultPage;
import com.rentalhub.fx.ExchangeRateSource;
import com.rentalhub.fx.ExchangeRates;
import com.rentalhub.fx.FxSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How exchange rates are cached, and how prices are converted and compared, with a
 * pretend provider and a clock the test moves by hand.
 */
class CurrencyServiceTest {

    private static final FxSettings SETTINGS = new FxSettings(Currency.INR, URI.create("http://unused"),
            Duration.ofHours(1), Duration.ofHours(48), Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(3));

    /** A real set of rates, from 15 Sep 2026: 1 USD = 95.674534 INR. */
    private static final Map<Currency, BigDecimal> REAL = Map.of(
            Currency.USD, new BigDecimal("1"),
            Currency.INR, new BigDecimal("95.674534"),
            Currency.EUR, new BigDecimal("0.865688"),
            Currency.GBP, new BigDecimal("0.740963"),
            Currency.AED, new BigDecimal("3.6725"));

    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-15T12:00:00Z"));
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicBoolean providerDown = new AtomicBoolean();

    /** Answers with REAL, published "now", unless the provider is down. */
    private final ExchangeRateSource provider = () -> {
        fetches.incrementAndGet();
        if (providerDown.get()) {
            throw new IllegalStateException("provider down");
        }
        return new ExchangeRates(REAL, clock.instant());
    };

    private final CurrencyService service = new CurrencyService(provider, SETTINGS, clock);

    // ---------------------------------------------------------------- caching

    @Test
    @DisplayName("rates are fetched once and reused for an hour")
    void fetchedOncePerHour() {
        service.currentRates();
        service.currentRates();
        clock.advance(Duration.ofMinutes(59));
        service.currentRates();
        assertThat(fetches).hasValue(1);

        clock.advance(Duration.ofMinutes(1));
        service.currentRates();
        assertThat(fetches).hasValue(2);
    }

    @Test
    @DisplayName("when a refresh fails, the last good rates stay in use, and the next try waits a minute")
    void outageKeepsTheLastGoodRates() {
        Instant firstAsOf = service.currentRates().orElseThrow().asOf();
        clock.advance(Duration.ofHours(1));
        providerDown.set(true);

        assertThat(service.currentRates()).hasValueSatisfying(rates -> assertThat(rates.asOf()).isEqualTo(firstAsOf));
        service.currentRates();
        assertThat(fetches).as("no second try within the minute").hasValue(2);

        clock.advance(Duration.ofMinutes(1));
        providerDown.set(false);
        assertThat(service.currentRates()).hasValueSatisfying(rates -> assertThat(rates.asOf()).isAfter(firstAsOf));
        assertThat(fetches).hasValue(3);
    }

    @Test
    @DisplayName("rates too old to trust are not shown, but the same currency still is")
    void staleRatesAreNotShown() {
        service.currentRates();
        providerDown.set(true);
        clock.advance(Duration.ofHours(49));

        assertThat(service.currentRates()).isEmpty();
        assertThat(service.convertForDisplay(new BigDecimal("2500"), Currency.INR, Currency.USD)).isEmpty();
        assertThat(service.convertForDisplay(new BigDecimal("2500.0000"), Currency.INR, Currency.INR))
                .hasValueSatisfying(same -> {
                    assertThat(same.amount()).isEqualByComparingTo("2500.00").hasScaleOf(2);
                    assertThat(same.rate()).isEqualByComparingTo("1");
                });
    }

    @Test
    @DisplayName("while one request fetches, the others carry on at once instead of waiting or fetching too")
    void noStampede() throws Exception {
        CountDownLatch insideFetch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CurrencyService slow = new CurrencyService(() -> {
            fetches.incrementAndGet();
            insideFetch.countDown();
            awaitQuietly(release);
            return new ExchangeRates(REAL, clock.instant());
        }, SETTINGS, clock);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Optional<ExchangeRates>> first = executor.submit(slow::currentRates);
            assertThat(insideFetch.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(slow.currentRates()).as("nothing yet, and no waiting for it").isEmpty();
            assertThat(fetches).hasValue(1);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
        }
        assertThat(slow.currentRates()).isPresent();
        assertThat(fetches).hasValue(1);
    }

    // -------------------------------------------------------------- display

    @Test
    @DisplayName("₹7,499.97 is shown as $78.39: rounded half-even to cents, with the rate and its date")
    void displayPrice() {
        DisplayPrice shown = service.convertForDisplay(new BigDecimal("7499.97"), Currency.INR, Currency.USD).orElseThrow();

        assertThat(shown.amount()).isEqualByComparingTo("78.39").hasScaleOf(2);
        assertThat(shown.currency()).isEqualTo(Currency.USD);
        assertThat(shown.rate()).isEqualByComparingTo("0.010452").hasScaleOf(6);
        assertThat(shown.ratesAsOf()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("a search page marks listings it couldn't convert, and says so")
    void pageWithoutRates() {
        providerDown.set(true);
        SearchResultPage page = new SearchResultPage(List.of(
                summary(Currency.INR, "2500.00"), summary(Currency.AED, "400.00")), 0, 20, 2, 1, false);

        SearchResultPage shown = service.inCurrency(page, Currency.INR);

        assertThat(shown.content().get(0).displayPrice()).isNotNull();
        assertThat(shown.content().get(1).displayPrice()).isNull();
        assertThat(shown.exchangeRatesUnavailable()).isTrue();
        assertThat(service.inCurrency(page, null)).as("no currency asked for: unchanged").isSameAs(page);
    }

    // -------------------------------------------------------------- ceilings

    @Test
    @DisplayName("a $100 limit becomes one ceiling per listing currency, each rounded down")
    void ceilingsInEveryCurrency() {
        PriceCeilings ceilings = service.ceilings(new BigDecimal("100"), Currency.USD);

        assertThat(ceilings.complete()).isTrue();
        assertThat(ceilings.byCurrency().get(Currency.USD)).isEqualByComparingTo("100.0000");
        assertThat(ceilings.byCurrency().get(Currency.INR)).isEqualByComparingTo("9567.4534");
        assertThat(ceilings.byCurrency().get(Currency.EUR)).isEqualByComparingTo("86.5688");
        // ₹100 is $1.045210...: rounded down, never up, so no listing slips in above the limit.
        assertThat(service.ceilings(new BigDecimal("100"), Currency.INR).byCurrency().get(Currency.USD))
                .isEqualByComparingTo("1.0452");
    }

    @Test
    @DisplayName("without rates, only listings in the limit's own currency can be compared")
    void ceilingsWithoutRates() {
        providerDown.set(true);

        PriceCeilings ceilings = service.ceilings(new BigDecimal("5000"), Currency.INR);

        assertThat(ceilings.complete()).isFalse();
        assertThat(ceilings.byCurrency()).containsOnlyKeys(Currency.INR);
    }

    // --------------------------------------------------------------- helpers

    private static PropertySummary summary(Currency currency, String price) {
        return new PropertySummary(1L, PropertyType.APARTMENT, "Listing", "Chennai", "India", new BigDecimal(price),
                currency, 2, 1, 1, null, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A clock the test moves forward by hand. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
