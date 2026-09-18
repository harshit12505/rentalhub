package com.rentalhub.ai;

import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.Favorite;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.Review;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.domain.repository.FavoriteRepository;
import com.rentalhub.domain.repository.ReviewRepository;
import com.rentalhub.service.CurrencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a guest's preference profile: cities, a price band, a usual party size, and the words
 * that keep coming up in what they save and write.
 *
 * <b>No model is involved.</b> Everything here is counting and averaging over the user's own
 * rows, which means a profile is free, instant, reproducible, and available even with no AI
 * key. The model only ever sees the one-line summary the profile writes about itself.
 *
 * Prices are converted into whichever currency most of their favourites use, so a band means
 * something even for a guest who saves places in three countries. That conversion is for
 * reading and comparing only, exactly like everywhere else since phase 5.
 */
@Service
public class PreferenceProfileService {

    /** How many cities and themes a profile keeps. Enough to be useful, short enough to read. */
    private static final int TOP_N = 3;

    private static final Pattern WORDS = Pattern.compile("[^\\p{IsAlphabetic}]+");

    /**
     * Words too common to say anything about taste. Short and hand-written on purpose: a
     * proper stop-word list is a library's job, and this only has to keep the themes readable.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "with", "for", "from", "this", "that", "here", "there", "near", "next",
            "very", "just", "your", "our", "you", "are", "was", "were", "has", "have", "had",
            "one", "two", "three", "all", "any", "but", "not", "its", "it's", "into", "over",
            "place", "stay", "room", "house", "home", "flat", "apartment", "villa", "cabin",
            "studio", "property", "listing", "night", "nights", "guest", "guests", "bedroom",
            "bedrooms", "bathroom", "bathrooms", "minutes", "minute", "walk", "close", "good",
            "great", "nice", "lovely", "clean", "well", "bright", "small", "large");

    private final FavoriteRepository favorites;
    private final BookingRepository bookings;
    private final ReviewRepository reviews;
    private final CurrencyService currencies;

    PreferenceProfileService(FavoriteRepository favorites,
                             BookingRepository bookings,
                             ReviewRepository reviews,
                             CurrencyService currencies) {
        this.favorites = favorites;
        this.bookings = bookings;
        this.reviews = reviews;
        this.currencies = currencies;
    }

    @Transactional(readOnly = true)
    public PreferenceProfile forUser(long userId) {
        List<Favorite> saved = favorites.findWithPropertyByUserIdOrderByCreatedAtDescIdDesc(userId);
        List<Property> savedListings = saved.stream().map(Favorite::getProperty).toList();
        List<Review> written = reviews.findByAuthorId(userId);
        List<Booking> booked = bookings.findByGuestIdOrderByCheckInDescIdDesc(userId);

        Currency currency = commonestCurrency(savedListings);
        List<BigDecimal> prices = pricesIn(savedListings, currency);

        return new PreferenceProfile(
                userId,
                saved.stream().map(favorite -> favorite.getProperty().getId()).toList(),
                commonest(savedListings.stream().map(Property::getCity).toList()),
                currency,
                prices.isEmpty() ? null : prices.getFirst(),
                prices.isEmpty() ? null : prices.getLast(),
                typicalGuests(booked),
                themes(savedListings, written),
                written.size(),
                averageRating(written));
    }

    /**
     * The currency to express the price band in: whichever most of their favourites use, so
     * the band is converted as little as possible. The app's default when they have none.
     */
    private Currency commonestCurrency(List<Property> savedListings) {
        return savedListings.stream()
                .collect(java.util.stream.Collectors.groupingBy(Property::getCurrency, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseGet(() -> currencies.orDefault(null));
    }

    /** Their saved listings' prices, in one currency, cheapest first. Ones that can't be converted are left out. */
    private List<BigDecimal> pricesIn(List<Property> savedListings, Currency currency) {
        return savedListings.stream()
                .map(listing -> currencies.convertForDisplay(listing.getPricePerNight(), listing.getCurrency(), currency))
                .flatMap(Optional::stream)
                .map(price -> price.amount())
                .sorted()
                .toList();
    }

    /** The party size they book for most often, rounded to a whole number of people. */
    private Integer typicalGuests(List<Booking> booked) {
        return booked.isEmpty() ? null
                : (int) Math.round(booked.stream().mapToInt(Booking::getGuests).average().orElse(0));
    }

    private BigDecimal averageRating(List<Review> written) {
        return written.isEmpty() ? null
                : BigDecimal.valueOf(written.stream().mapToInt(Review::getRating).average().orElse(0))
                        .setScale(1, RoundingMode.HALF_EVEN);
    }

    /**
     * The words that come up most in what they saved and what they wrote about their stays.
     *
     * Word counting, not a model: for "recurring themes" it is enough, it costs nothing, and
     * you can always explain why a word is in the list.
     */
    private List<String> themes(List<Property> savedListings, List<Review> written) {
        List<String> words = new ArrayList<>();
        savedListings.forEach(listing -> {
            words.addAll(wordsOf(listing.getTitle()));
            words.addAll(wordsOf(listing.getDescription()));
        });
        written.forEach(review -> words.addAll(wordsOf(review.getComment())));
        return commonest(words);
    }

    private List<String> wordsOf(String text) {
        if (text == null) {
            return List.of();
        }
        return WORDS.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(word -> word.length() > 3 && !STOP_WORDS.contains(word))
                .toList();
    }

    /** The {@value #TOP_N} values that appear most often, commonest first; ties keep their first-seen order. */
    private List<String> commonest(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        values.forEach(value -> counts.merge(value, 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry<String, Integer>::getValue).reversed())
                .limit(TOP_N)
                .map(Map.Entry::getKey)
                .toList();
    }
}
