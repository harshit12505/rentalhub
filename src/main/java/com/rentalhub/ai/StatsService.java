package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.dto.DisplayPrice;
import com.rentalhub.service.CurrencyService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Answers questions about a guest's own numbers, with SQL only.
 *
 * <b>Why a model never sees these.</b> "How much have I spent on bookings?" has exactly one
 * right answer, and it is a sum the database can do. Handing the rows to a language model and
 * asking it to add them up would be slower, cost quota, and occasionally be wrong in a way
 * that looks completely confident. So the question is routed: anything that asks for a number
 * is answered here, and only the open-ended "find me somewhere..." questions reach the model.
 *
 * This is the whole point of the phase in one class: use the model for language, and the
 * database for facts.
 *
 * The sentences themselves come from messages.properties, so they are translated like every
 * other piece of text in the app.
 */
@Service
public class StatsService {

    private static final Pattern MONEY = Pattern.compile("\\b(spent|spend|spending|cost|paid|much)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STAYS = Pattern.compile("\\b(booking|bookings|stay|stays|trip|trips)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAVED = Pattern.compile("\\b(favou?rite|favou?rites|saved|wishlist)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REVIEWS = Pattern.compile("\\b(review|reviews|rating|ratings|stars?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOW_MANY = Pattern.compile("\\bhow many\\b|\\bnumber of\\b|\\bcount\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPICAL = Pattern.compile("\\b(average|typical|usually|normally)\\b",
            Pattern.CASE_INSENSITIVE);

    private final BookingRepository bookings;
    private final CurrencyService currencies;
    private final MessageSource messages;

    StatsService(BookingRepository bookings, CurrencyService currencies, MessageSource messages) {
        this.bookings = bookings;
        this.currencies = currencies;
        this.messages = messages;
    }

    /**
     * The answer to a statistics question, already written out in the caller's language.
     *
     * @param displayCurrency the currency to report money in, where a rate exists for it
     */
    public String answer(ParsedQuery query, PreferenceProfile profile, Currency displayCurrency) {
        String question = query.text();
        Locale locale = LocaleContextHolder.getLocale();

        if (REVIEWS.matcher(question).find()) {
            return reviews(profile, locale);
        }
        if (HOW_MANY.matcher(question).find() && SAVED.matcher(question).find()) {
            return favourites(profile, locale);
        }
        if (HOW_MANY.matcher(question).find() && STAYS.matcher(question).find()) {
            return bookingCounts(profile, locale);
        }
        if (TYPICAL.matcher(question).find() && SAVED.matcher(question).find()) {
            return usualPrices(profile, locale);
        }
        if (MONEY.matcher(question).find()) {
            return spend(profile, displayCurrency, locale);
        }
        return overview(profile, locale);
    }

    /** What they have paid, in one currency where the rates allow it. */
    private String spend(PreferenceProfile profile, Currency displayCurrency, Locale locale) {
        List<BookingRepository.SpendByCurrency> spend = bookings.findSpendByGuestId(profile.userId());
        if (spend.isEmpty()) {
            return say("ai.stats.spend.none", locale);
        }
        long paidBookings = spend.stream().mapToLong(BookingRepository.SpendByCurrency::getBookings).sum();
        return say("ai.stats.spend", locale, money(spend, displayCurrency, locale), paidBookings);
    }

    private String bookingCounts(PreferenceProfile profile, Locale locale) {
        long all = bookings.countByGuestId(profile.userId());
        long confirmed = bookings.countByGuestIdAndStatus(profile.userId(), BookingStatus.CONFIRMED);
        long cancelled = bookings.countByGuestIdAndStatus(profile.userId(), BookingStatus.CANCELLED);
        return say("ai.stats.bookings", locale, all, confirmed, cancelled);
    }

    private String favourites(PreferenceProfile profile, Locale locale) {
        if (profile.favouriteIds().isEmpty()) {
            return say("ai.stats.favourites.none", locale);
        }
        if (profile.cities().isEmpty()) {
            return say("ai.stats.favourites", locale, profile.favouriteIds().size());
        }
        return say("ai.stats.favourites.cities", locale,
                profile.favouriteIds().size(), String.join(", ", profile.cities()));
    }

    private String usualPrices(PreferenceProfile profile, Locale locale) {
        if (profile.typicalLow() == null) {
            return say("ai.stats.prices.none", locale);
        }
        return say("ai.stats.prices", locale,
                amount(profile.typicalLow(), profile.currency(), locale),
                amount(profile.typicalHigh(), profile.currency(), locale),
                profile.currency().name());
    }

    private String reviews(PreferenceProfile profile, Locale locale) {
        if (profile.averageRating() == null) {
            return say("ai.stats.reviews.none", locale);
        }
        return say("ai.stats.reviews", locale, profile.reviewsWritten(), profile.averageRating());
    }

    private String overview(PreferenceProfile profile, Locale locale) {
        return say("ai.stats.overview", locale,
                profile.favouriteIds().size(),
                bookings.countByGuestId(profile.userId()),
                profile.reviewsWritten());
    }

    /**
     * Money totals as one phrase.
     *
     * Totals in different currencies are added together only if every one of them can be
     * converted; otherwise they are listed side by side, because a total that quietly left
     * out the euros would be a lie. The conversion is display-only, as always.
     */
    private String money(List<BookingRepository.SpendByCurrency> spend, Currency displayCurrency, Locale locale) {
        BigDecimal total = BigDecimal.ZERO;
        for (BookingRepository.SpendByCurrency row : spend) {
            Optional<DisplayPrice> converted =
                    currencies.convertForDisplay(row.getTotal(), row.getCurrency(), displayCurrency);
            if (converted.isEmpty()) {
                return spend.stream()
                        .map(each -> amount(each.getTotal(), each.getCurrency(), locale) + " " + each.getCurrency())
                        .collect(Collectors.joining(", "));
            }
            total = total.add(converted.get().amount());
        }
        return amount(total, displayCurrency, locale) + " " + displayCurrency;
    }

    /** An amount written the way this locale writes numbers, at the currency's own decimals. */
    private String amount(BigDecimal amount, Currency currency, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(currency.fractionDigits());
        format.setMaximumFractionDigits(currency.fractionDigits());
        return format.format(currency.round(amount));
    }

    private String say(String key, Locale locale, Object... arguments) {
        return messages.getMessage(key, arguments, locale);
    }
}
