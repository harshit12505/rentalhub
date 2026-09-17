package com.rentalhub.domain.model.enums;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    /**
     * The amount at this currency's number of decimals. Half-even ("banker's") rounding:
     * an exact half goes to the even neighbour (2.345 → 2.34, 2.355 → 2.36), so across
     * many roundings the errors cancel out instead of always pushing totals up.
     */
    public BigDecimal round(BigDecimal amount) {
        return amount.setScale(fractionDigits(), RoundingMode.HALF_EVEN);
    }

    /**
     * The amount in this currency's smallest unit, which is how payment providers take it:
     * ₹7,500.00 is 750000 paise, $139.99 is 13999 cents.
     *
     * Exact, never rounded. An amount with a fraction of the smallest unit (₹7,500.005) is a
     * bug further up, and silently rounding it here would charge a different sum from the
     * one the guest was shown. The classic double-based version, {@code (long) (price * 100)},
     * is worse still: it truncates, and turns ₹2,499.99 into 249998 paise.
     *
     * @throws ArithmeticException if the amount has a fraction of the smallest unit
     */
    public long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(fractionDigits()).longValueExact();
    }
}
