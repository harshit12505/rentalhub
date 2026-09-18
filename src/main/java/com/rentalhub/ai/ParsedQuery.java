package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.Currency;

import java.math.BigDecimal;

/**
 * What a question in plain English turned out to be asking for.
 *
 * The crisp parts (a city, a budget, a party size) become real filters in SQL. Everything
 * else stays as text, for the vector search to answer by meaning.
 *
 * @param text            the question as asked
 * @param intent          whether this is a request for listings or for a number
 * @param city            a city named in the question, matched against the cities that exist
 * @param maxPrice        a budget, in {@code currency}
 * @param currency        the currency the budget is in
 * @param guests          how many people are staying
 * @param likeFavourites  true when the question asks for something like what they already save
 */
public record ParsedQuery(
        String text,
        Intent intent,
        String city,
        BigDecimal maxPrice,
        Currency currency,
        Integer guests,
        boolean likeFavourites) {

    /** What kind of answer the question wants. */
    public enum Intent {
        /** "Find me somewhere ..." — listings. */
        RECOMMEND,
        /** "What's my average ...?" — a number, which SQL can answer without a model. */
        STATS
    }

    /** True when something in the question can be turned into a WHERE clause. */
    public boolean hasFilters() {
        return city != null || maxPrice != null || guests != null;
    }
}
