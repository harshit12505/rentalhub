package com.rentalhub.service;

import com.rentalhub.domain.model.enums.Currency;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * One price limit, expressed in every listing currency.
 *
 * "At most $100 a night" becomes "at most ₹9,567.4534, or €86.5688, or $100, ...", so the
 * database can compare each listing with the limit in the listing's own currency. Converting
 * the one limit, rather than every listing's price, keeps the query a plain comparison per
 * currency, and no converted price is ever stored.
 *
 * @param byCurrency the ceiling for listings priced in each currency. A currency that is
 *                   missing (no exchange rate for it) matches no listings
 * @param complete   false when some currency had no rate, so the search could only compare
 *                   some of the listings
 */
public record PriceCeilings(Map<Currency, BigDecimal> byCurrency, boolean complete) {

    public PriceCeilings {
        // An EnumMap keeps the currencies in a fixed order, so the same search builds the same SQL.
        EnumMap<Currency, BigDecimal> copy = new EnumMap<>(Currency.class);
        copy.putAll(byCurrency);
        byCurrency = Collections.unmodifiableMap(copy);
    }
}
