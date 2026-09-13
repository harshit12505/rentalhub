package com.rentalhub.dto;

import java.util.List;

/**
 * One page of search results.
 *
 * Our own record rather than Spring Data's Page: this is what gets cached in Redis
 * as JSON, and Spring's PageImpl has no stable JSON shape to read back from.
 */
public record SearchResultPage(
        List<PropertySummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public SearchResultPage {
        content = List.copyOf(content);
    }
}
