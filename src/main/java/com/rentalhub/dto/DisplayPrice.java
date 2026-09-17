package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.Currency;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An amount shown in the currency the viewer asked for. Display only.
 *
 * Never stored and never cached: exchange rates move, so a converted figure is true only
 * for a moment. What a guest is charged is always the listing's own price in the listing's
 * own currency; this answers "roughly how much is that in my money?".
 *
 * @param amount    the converted amount, at the display currency's number of decimals
 * @param currency  the currency the viewer asked for
 * @param rate      how many units of {@code currency} one unit of the original currency
 *                  buys, to six decimals; exactly 1 when no conversion was needed
 * @param ratesAsOf when the rates were last updated by their provider; null when no
 *                  conversion was needed
 */
public record DisplayPrice(BigDecimal amount, Currency currency, BigDecimal rate, Instant ratesAsOf) {
}
