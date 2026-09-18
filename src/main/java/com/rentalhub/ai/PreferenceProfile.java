package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * What this guest seems to like, worked out from what they have saved, booked and written.
 *
 * It is deliberately plain data: counts, a price band, a few words. Every part of it comes
 * from SQL over the user's own rows, so building a profile costs nothing and works with no
 * AI key at all. The model never sees the rows, only this summary.
 *
 * @param favouriteIds    the listings they saved, newest first; the "taste vector" is the
 *                        average of these listings' embeddings (EmbeddingIndexStore)
 * @param cities          the cities they save most, most-saved first
 * @param currency        the currency most of their favourites are priced in, which is the
 *                        one the price band is expressed in
 * @param typicalLow      the cheapest and dearest of their saved listings, converted into
 * @param typicalHigh     {@code currency}. Null when they have saved nothing
 * @param typicalGuests   the party size they usually book for; null if they have never booked
 * @param themes          words that keep coming up in what they save and write
 * @param reviewsWritten  how many reviews they have left
 * @param averageRating   the average rating they give, or null if they have never reviewed
 */
public record PreferenceProfile(
        long userId,
        List<Long> favouriteIds,
        List<String> cities,
        Currency currency,
        BigDecimal typicalLow,
        BigDecimal typicalHigh,
        Integer typicalGuests,
        List<String> themes,
        int reviewsWritten,
        BigDecimal averageRating) {

    public PreferenceProfile {
        favouriteIds = List.copyOf(favouriteIds);
        cities = List.copyOf(cities);
        themes = List.copyOf(themes);
    }

    /** Nothing saved, booked or written: there is no taste to go on. */
    public boolean isEmpty() {
        return favouriteIds.isEmpty() && typicalGuests == null && reviewsWritten == 0;
    }

    /**
     * The profile as one line of English, which is what goes into the prompt.
     *
     * Written out rather than handed over as JSON, because it is read by a language model and
     * by a person debugging the same prompt.
     */
    public String summary() {
        if (isEmpty()) {
            return "This guest has not saved, booked or reviewed anything yet.";
        }
        StringBuilder text = new StringBuilder("This guest ");
        if (!favouriteIds.isEmpty()) {
            text.append("has saved ").append(favouriteIds.size()).append(" listing(s)");
            if (!cities.isEmpty()) {
                text.append(", mostly in ").append(String.join(" and ", cities));
            }
            if (typicalLow != null && typicalHigh != null) {
                text.append(", priced between ").append(typicalLow.toPlainString())
                        .append(" and ").append(typicalHigh.toPlainString())
                        .append(' ').append(currency.name()).append(" a night");
            }
            text.append(". ");
        }
        if (typicalGuests != null) {
            text.append("They usually book for ").append(typicalGuests).append(" guest(s). ");
        }
        if (!themes.isEmpty()) {
            text.append("Words that keep coming up in what they like: ")
                    .append(String.join(", ", themes)).append(". ");
        }
        if (averageRating != null) {
            text.append("They have written ").append(reviewsWritten)
                    .append(" review(s), averaging ").append(averageRating.toPlainString()).append(" stars.");
        }
        return text.toString().strip();
    }
}
