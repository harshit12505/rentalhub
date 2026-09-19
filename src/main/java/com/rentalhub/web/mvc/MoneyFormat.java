package com.rentalhub.web.mvc;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.DisplayPrice;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;

/**
 * Amounts of money as the pages show them: grouped the way the reader's language groups
 * numbers, at the currency's own number of decimals, with the currency's code.
 *
 * Templates call it as {@code ${@money.format(listing.pricePerNight, listing.currency)}}. The
 * code rather than a symbol, because "$" alone does not say whose dollar, and ₹ and AED have
 * no symbol every font can show.
 */
@Component("money")
public class MoneyFormat {

    public String format(BigDecimal amount, Currency currency) {
        if (amount == null || currency == null) {
            return "";
        }
        NumberFormat format = NumberFormat.getNumberInstance(LocaleContextHolder.getLocale());
        format.setMinimumFractionDigits(currency.fractionDigits());
        format.setMaximumFractionDigits(currency.fractionDigits());
        return format.format(currency.round(amount)) + " " + currency.name();
    }

    public String format(DisplayPrice price) {
        return price == null ? "" : format(price.amount(), price.currency());
    }
}
