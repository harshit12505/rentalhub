package com.rentalhub.dto;

import java.util.List;

/**
 * One page of search results.
 *
 * Our own record rather than Spring Data's Page: this is what gets cached in Redis
 * as JSON, and Spring's PageImpl has no stable JSON shape to read back from.
 *
 * @param exchangeRatesUnavailable true when prices could not be converted: a maxPrice then
 *                                 matched only listings priced in its own currency, and
 *                                 listings in other currencies have no displayPrice. Pages
 *                                 like that are not cached, so they last only as long as
 *                                 the outage
 */
public record SearchResultPage(
        List<PropertySummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean exchangeRatesUnavailable) {

    public SearchResultPage {
        content = List.copyOf(content);
    }

    /** The same page with its listings replaced (by copies showing a display price). */
    public SearchResultPage withContent(List<PropertySummary> newContent, boolean ratesUnavailable) {
        return new SearchResultPage(newContent, page, size, totalElements, totalPages, ratesUnavailable);
    }
}
