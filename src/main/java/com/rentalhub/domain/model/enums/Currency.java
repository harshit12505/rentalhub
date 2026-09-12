package com.rentalhub.domain.model.enums;

/**
 * Currencies a host may price a listing in.
 * A booking is always charged and stored in the listing's own currency;
 * anything else the user sees is a display-only conversion.
 */
public enum Currency {
    INR, USD, EUR, GBP, AED;

    /**
     * How many decimal places this currency really uses: 2 for all of these, 0 for
     * currencies like JPY. Read from the JDK's ISO 4217 data rather than hard-coded.
     */
    public int fractionDigits() {
        return java.util.Currency.getInstance(name()).getDefaultFractionDigits();
    }
}
