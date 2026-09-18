package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.dto.DisplayPrice;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.support.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Questions with one right answer, answered from the database.
 *
 * The sums are the interesting part: money in different currencies is either converted into
 * one, or listed separately, and never quietly added together.
 */
class StatsServiceTest {

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final CurrencyService currencies = mock(CurrencyService.class);

    private StatsService stats;

    @BeforeEach
    void setUp() {
        stats = new StatsService(bookings, currencies, TestMessages.source());
    }

    @Test
    @DisplayName("spend in several currencies is reported as one total when every rate is there")
    void addsUpSpendWhenItCan() {
        when(bookings.findSpendByGuestId(anyLong())).thenReturn(List.of(
                spend(Currency.INR, "12000.00", 2), spend(Currency.USD, "50.00", 1)));
        // 1 USD = 80 INR, as in the integration tests.
        when(currencies.convertForDisplay(any(), eq(Currency.INR), eq(Currency.INR)))
                .thenAnswer(call -> Optional.of(new DisplayPrice(call.getArgument(0), Currency.INR,
                        BigDecimal.ONE, null)));
        when(currencies.convertForDisplay(any(), eq(Currency.USD), eq(Currency.INR)))
                .thenReturn(Optional.of(new DisplayPrice(new BigDecimal("4000.00"), Currency.INR,
                        new BigDecimal("80"), null)));

        String answer = stats.answer(ask("how much have I spent on bookings?"), profile(), Currency.INR);

        assertThat(answer).isEqualTo("You have spent 16,000.00 INR on 3 paid booking(s).");
    }

    @Test
    @DisplayName("with a rate missing the currencies are listed side by side, never summed")
    void neverAddsWhatItCannotConvert() {
        when(bookings.findSpendByGuestId(anyLong())).thenReturn(List.of(
                spend(Currency.INR, "12000.00", 2), spend(Currency.EUR, "90.00", 1)));
        when(currencies.convertForDisplay(any(), any(), any())).thenReturn(Optional.empty());

        String answer = stats.answer(ask("how much have I spent?"), profile(), Currency.INR);

        assertThat(answer).isEqualTo("You have spent 12,000.00 INR, 90.00 EUR on 3 paid booking(s).");
    }

    @Test
    @DisplayName("counts come from the database, and the cities from the profile")
    void countsAndCities() {
        when(bookings.countByGuestId(7L)).thenReturn(4L);
        when(bookings.countByGuestIdAndStatus(7L, BookingStatus.CONFIRMED)).thenReturn(3L);
        when(bookings.countByGuestIdAndStatus(7L, BookingStatus.CANCELLED)).thenReturn(1L);

        assertThat(stats.answer(ask("how many stays have I booked?"), profile(), Currency.INR))
                .isEqualTo("You have made 4 booking(s): 3 confirmed and 1 cancelled.");
        assertThat(stats.answer(ask("how many places have I saved?"), profile(), Currency.INR))
                .isEqualTo("You have saved 2 listing(s), mostly in Goa.");
    }

    @Test
    @DisplayName("the usual price band and the ratings come straight from the profile")
    void bandAndRatings() {
        assertThat(stats.answer(ask("what do I usually pay for my favourites?"), profile(), Currency.INR))
                .isEqualTo("Your saved listings cost between 3,000.00 and 7,000.00 INR a night.");
        assertThat(stats.answer(ask("what rating do I give on average?"), profile(), Currency.INR))
                .isEqualTo("You have written 3 review(s), averaging 4.5 stars.");
    }

    @Test
    @DisplayName("a question it does not recognise gets an overview rather than nothing")
    void fallsBackToAnOverview() {
        when(bookings.countByGuestId(7L)).thenReturn(4L);

        assertThat(stats.answer(ask("tell me about my account"), profile(), Currency.INR))
                .isEqualTo("You have saved 2 listing(s), made 4 booking(s) and written 3 review(s).");
    }

    private static ParsedQuery ask(String question) {
        return new ParsedQuery(question, ParsedQuery.Intent.STATS, null, null, Currency.INR, null, false);
    }

    private static PreferenceProfile profile() {
        return new PreferenceProfile(7L, List.of(1L, 2L), List.of("Goa"), Currency.INR,
                new BigDecimal("3000.00"), new BigDecimal("7000.00"), 2, List.of("beach"), 3,
                new BigDecimal("4.5"));
    }

    /** The projection as plain data, rather than a mock inside another mock's arguments. */
    private static BookingRepository.SpendByCurrency spend(Currency currency, String total, long count) {
        return new BookingRepository.SpendByCurrency() {

            @Override
            public Currency getCurrency() {
                return currency;
            }

            @Override
            public BigDecimal getTotal() {
                return new BigDecimal(total);
            }

            @Override
            public long getBookings() {
                return count;
            }
        };
    }
}
