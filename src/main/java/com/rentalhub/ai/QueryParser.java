package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.service.CurrencyService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a question in plain English into filters, using rules rather than a model.
 *
 * <b>Why rules.</b> The spec allows either, and asks for the reasoning. A model would be a
 * second network call before any work starts, a second thing that can fail or be rate-limited,
 * and a second source of answers that differ between runs. What it would extract here is three
 * crisp things — a city, a budget, a party size — which a small parser gets right, in
 * microseconds, with tests that pin every case. The model is kept for the job only it can do:
 * writing the answer.
 *
 * The fuzzy half of the question ("quiet", "by the sea", "for a family") is not parsed at all.
 * That is what the embeddings are for.
 */
@Component
public class QueryParser {

    /** "under 5000", "below ₹5,000", "cheaper than $100", "up to 80 eur". */
    private static final Pattern BUDGET = Pattern.compile(
            "(?:under|below|less than|cheaper than|up to|max(?:imum)?(?: of)?)\\s*"
                    + "([₹$€£])?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*"
                    + "(inr|usd|eur|gbp|aed|rupees?|dollars?|euros?|pounds?|dirhams?)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * "for 4", "4 guests", "sleeps 6", "party of 3". The lookahead keeps "for 3 nights" out:
     * that is a length of stay, and reading it as a party size would silently drop every
     * listing that sleeps fewer than three.
     */
    private static final Pattern PARTY = Pattern.compile(
            "(?:for|sleeps?|party of)\\s+([0-9]{1,2})\\b(?!\\s*(?:nights?|days?|weeks?|months?))"
                    + "|\\b([0-9]{1,2})\\s*(?:people|guests|persons|adults)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern A_COUPLE = Pattern.compile("\\b(a couple|two of us|my partner and i)\\b",
            Pattern.CASE_INSENSITIVE);

    /** "like my favourites", "similar to the ones I saved", "my usual taste". */
    private static final Pattern LIKE_FAVOURITES = Pattern.compile(
            "(like|similar to)\\s+(my|the)\\s+(favou?rites?|saved|usual)|my (usual )?taste|more like (these|those|them)",
            Pattern.CASE_INSENSITIVE);

    /** "but cheaper", "something cheap", "on a budget" — a budget with no number in it. */
    private static final Pattern VAGUELY_CHEAPER = Pattern.compile("\\b(cheaper|cheap|budget|affordable)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A question about a number rather than a place: it needs a measure and a subject. */
    private static final Pattern MEASURE = Pattern.compile(
            "\\b(average|typical|typically|usually|normally|how much|how many|total|spent|spend|spending|pay|paying)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJECT = Pattern.compile(
            "\\b(favou?rites?|saved|booking|bookings|stay|stays|trip|trips|review|reviews|rating|ratings|stars?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Currency> CURRENCY_WORDS = Map.ofEntries(
            Map.entry("₹", Currency.INR), Map.entry("inr", Currency.INR),
            Map.entry("rupee", Currency.INR), Map.entry("rupees", Currency.INR),
            Map.entry("$", Currency.USD), Map.entry("usd", Currency.USD),
            Map.entry("dollar", Currency.USD), Map.entry("dollars", Currency.USD),
            Map.entry("€", Currency.EUR), Map.entry("eur", Currency.EUR),
            Map.entry("euro", Currency.EUR), Map.entry("euros", Currency.EUR),
            Map.entry("£", Currency.GBP), Map.entry("gbp", Currency.GBP),
            Map.entry("pound", Currency.GBP), Map.entry("pounds", Currency.GBP),
            Map.entry("aed", Currency.AED),
            Map.entry("dirham", Currency.AED), Map.entry("dirhams", Currency.AED));

    private final PropertyRepository properties;
    private final CurrencyService currencies;

    QueryParser(PropertyRepository properties, CurrencyService currencies) {
        this.properties = properties;
        this.currencies = currencies;
    }

    /** Parses a question against the cities that actually exist and this guest's own history. */
    public ParsedQuery parse(String question, PreferenceProfile profile) {
        return parse(question, profile, properties.findDistinctActiveCities(), currencies.orDefault(null));
    }

    /**
     * The rules themselves, with nothing injected, so every case can be pinned by a unit test.
     *
     * @param knownCities    the cities that have listings; a city is only recognised if one exists
     * @param fallbackCurrency what a bare number means when the question names no currency
     */
    static ParsedQuery parse(String question, PreferenceProfile profile, List<String> knownCities,
                             Currency fallbackCurrency) {
        String text = question == null ? "" : question.strip();
        Currency currency = profile != null && !profile.favouriteIds().isEmpty() ? profile.currency() : fallbackCurrency;

        Budget budget = budgetIn(text, currency);
        if (budget == null && VAGUELY_CHEAPER.matcher(text).find() && profile != null && profile.typicalHigh() != null) {
            // "but cheaper", with no number: cheaper than the middle of what they usually save.
            BigDecimal middle = profile.typicalLow().add(profile.typicalHigh())
                    .divide(BigDecimal.valueOf(2), profile.currency().fractionDigits(), RoundingMode.HALF_EVEN);
            budget = new Budget(middle, profile.currency());
        }

        return new ParsedQuery(
                text,
                intentOf(text),
                cityIn(text, knownCities),
                budget == null ? null : budget.amount(),
                budget == null ? null : budget.currency(),
                partySizeIn(text),
                LIKE_FAVOURITES.matcher(text).find());
    }

    private static ParsedQuery.Intent intentOf(String text) {
        boolean asksForANumber = MEASURE.matcher(text).find() && SUBJECT.matcher(text).find();
        return asksForANumber ? ParsedQuery.Intent.STATS : ParsedQuery.Intent.RECOMMEND;
    }

    /**
     * The city named in the question, if any listing is in it.
     *
     * Matched against the real cities rather than guessed, so "Goa" is a place and "beach" is
     * not, and the longest match wins ("New Delhi" over "Delhi").
     */
    private static String cityIn(String text, List<String> knownCities) {
        String lower = text.toLowerCase(Locale.ROOT);
        return knownCities.stream()
                .filter(city -> lower.contains(city.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private static Budget budgetIn(String text, Currency fallback) {
        Matcher budget = BUDGET.matcher(text);
        if (!budget.find()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(budget.group(2).replace(",", ""));
        Currency named = currencyOf(budget.group(1));
        if (named == null) {
            named = currencyOf(budget.group(3));
        }
        return new Budget(amount, named == null ? fallback : named);
    }

    private static Currency currencyOf(String word) {
        return word == null ? null : CURRENCY_WORDS.get(word.toLowerCase(Locale.ROOT));
    }

    private static Integer partySizeIn(String text) {
        Matcher party = PARTY.matcher(text);
        while (party.find()) {
            String number = party.group(1) != null ? party.group(1) : party.group(2);
            int guests = Integer.parseInt(number);
            // "for 3 nights" is not a party size, and neither is a year: keep it plausible.
            if (guests >= 1 && guests <= 30) {
                return guests;
            }
        }
        return A_COUPLE.matcher(text).find() ? 2 : null;
    }

    private record Budget(BigDecimal amount, Currency currency) {
    }
}
