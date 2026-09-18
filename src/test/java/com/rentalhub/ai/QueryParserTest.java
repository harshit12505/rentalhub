package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that turn a question into filters.
 *
 * All of it is pure: no Spring, no database, no model. That is the point of doing this with
 * rules instead of an LLM, so the tests are allowed to be this specific.
 */
class QueryParserTest {

    private static final List<String> CITIES = List.of("Goa", "Delhi", "New Delhi", "Chennai");

    private static ParsedQuery parse(String question) {
        return QueryParser.parse(question, noHistory(), CITIES, Currency.INR);
    }

    private static PreferenceProfile noHistory() {
        return new PreferenceProfile(1L, List.of(), List.of(), Currency.INR, null, null, null, List.of(), 0, null);
    }

    @Test
    @DisplayName("pulls city, party size and budget out of one ordinary sentence")
    void readsTheCrispParts() {
        ParsedQuery query = parse("somewhere quiet in Goa for 2 under 5000");

        assertThat(query.city()).isEqualTo("Goa");
        assertThat(query.guests()).isEqualTo(2);
        assertThat(query.maxPrice()).isEqualByComparingTo("5000");
        assertThat(query.currency()).isEqualTo(Currency.INR);
        assertThat(query.intent()).isEqualTo(ParsedQuery.Intent.RECOMMEND);
        assertThat(query.likeFavourites()).isFalse();
        assertThat(query.hasFilters()).isTrue();
    }

    @Test
    @DisplayName("a city is only recognised if listings exist there, and the longest name wins")
    void matchesRealCities() {
        assertThat(parse("a flat in New Delhi").city()).isEqualTo("New Delhi");
        assertThat(parse("a flat in Delhi").city()).isEqualTo("Delhi");
        assertThat(parse("somewhere beachy").city()).isNull();
        assertThat(parse("anything in Tokyo").city())
                .as("no listings in Tokyo, so it is not a filter")
                .isNull();
    }

    @Test
    @DisplayName("reads the currency from a symbol, a code or a word, and the default otherwise")
    void readsTheCurrency() {
        assertThat(parse("under $120").currency()).isEqualTo(Currency.USD);
        assertThat(parse("up to 80 eur").currency()).isEqualTo(Currency.EUR);
        assertThat(parse("cheaper than 90 pounds").currency()).isEqualTo(Currency.GBP);
        assertThat(parse("below 7,500").currency()).isEqualTo(Currency.INR);
        assertThat(parse("below ₹7,500").maxPrice()).isEqualByComparingTo("7500");
    }

    @Test
    @DisplayName("a length of stay is not a party size")
    void doesNotConfuseNightsWithGuests() {
        assertThat(parse("a cabin for 3 nights").guests()).isNull();
        assertThat(parse("a cabin for 3").guests()).isEqualTo(3);
        assertThat(parse("room for 6 guests").guests()).isEqualTo(6);
        assertThat(parse("a place for a couple").guests()).isEqualTo(2);
    }

    @Test
    @DisplayName("a question about the guest's own numbers is answered by SQL, not by a model")
    void routesStatisticsQuestions() {
        assertThat(parse("how much have I spent on bookings?").intent()).isEqualTo(ParsedQuery.Intent.STATS);
        assertThat(parse("what is the average price of my favourites?").intent()).isEqualTo(ParsedQuery.Intent.STATS);
        assertThat(parse("what do I usually pay for my favourites?").intent()).isEqualTo(ParsedQuery.Intent.STATS);
        assertThat(parse("what rating do I give on average?").intent()).isEqualTo(ParsedQuery.Intent.STATS);
        assertThat(parse("how many guests can this sleep").intent())
                .as("a measure word alone is not a question about their history")
                .isEqualTo(ParsedQuery.Intent.RECOMMEND);
    }

    @Test
    @DisplayName("\"like my favourites, but cheaper\" becomes a taste search with a real ceiling")
    void usesTheProfileForVagueWords() {
        PreferenceProfile profile = new PreferenceProfile(7L, List.of(1L, 2L), List.of("Goa"), Currency.INR,
                new BigDecimal("3000.00"), new BigDecimal("7000.00"), 2, List.of("beach"), 0, null);

        ParsedQuery query = QueryParser.parse("somewhere like my favourites but cheaper", profile, CITIES,
                Currency.USD);

        assertThat(query.likeFavourites()).isTrue();
        assertThat(query.maxPrice())
                .as("the middle of the band they usually save in")
                .isEqualByComparingTo("5000.00");
        assertThat(query.currency())
                .as("their own currency, not the fallback")
                .isEqualTo(Currency.INR);
    }

    @Test
    @DisplayName("an empty question parses to no filters instead of throwing")
    void survivesNothing() {
        ParsedQuery query = parse(null);

        assertThat(query.text()).isEmpty();
        assertThat(query.hasFilters()).isFalse();
        assertThat(query.intent()).isEqualTo(ParsedQuery.Intent.RECOMMEND);
    }
}
