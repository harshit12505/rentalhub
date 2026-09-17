package com.rentalhub.fx;

/**
 * Where exchange rates come from: a live provider in the application (ExchangeRateApiSource),
 * fixed rates in the tests.
 */
public interface ExchangeRateSource {

    /**
     * The provider's latest rates. Throws when they cannot be had (network trouble, an error
     * response); CurrencyService then carries on with the last good set it has.
     */
    ExchangeRates fetchLatest();
}
