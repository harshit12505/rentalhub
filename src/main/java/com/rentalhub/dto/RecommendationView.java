package com.rentalhub.dto;

import com.rentalhub.ai.ParsedQuery;

import java.util.List;

/**
 * The answer to a question asked in plain English.
 *
 * The written answer and the listings travel together on purpose: the text is only ever a
 * description of the cards below it, and a client that does not trust the text can ignore it
 * and show the cards. Everything the answer mentions is in {@code suggestions}.
 *
 * @param question   the question as asked, echoed back
 * @param intent     whether it was answered with listings or with a number from the database
 * @param answer     the sentences to show. Written by the model when one is configured, and
 *                   by the app itself when it is not, so this is never empty
 * @param aiUsed     true when a language model wrote the answer
 * @param semantic   true when the listings were ranked by meaning (embeddings), false when
 *                   they came from an ordinary filtered search
 * @param exchangeRatesUnavailable true when a budget could only be compared against some
 *                   currencies, because a rate was missing (as in phase 5 search)
 */
public record RecommendationView(
        String question,
        ParsedQuery.Intent intent,
        String answer,
        List<Suggestion> suggestions,
        boolean aiUsed,
        boolean semantic,
        boolean exchangeRatesUnavailable) {

    /**
     * One suggested listing.
     *
     * @param similarity how close it was in meaning, from 1 to 0, or null when the ranking
     *                   did not use embeddings. Exposed because a reviewer of this project
     *                   should be able to see why a listing was suggested
     */
    public record Suggestion(PropertySummary listing, Double similarity) {
    }
}
