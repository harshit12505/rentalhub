package com.rentalhub.web.mvc;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.dto.SearchResultPage;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.service.ReviewService;
import com.rentalhub.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;

/**
 * The home page: search, and a page of listing cards.
 *
 * The same SearchService as the REST API, so the same Redis-cached pages and the same fair
 * comparison of prices across currencies. The cards' ratings are loaded for the whole page in
 * one query, next to the cached results rather than inside them (see ReviewService.ratingsFor).
 */
@Controller
public class HomeController {

    /** Twelve cards: three rows of four on a wide screen, and a whole number of rows on most others. */
    static final int PAGE_SIZE = 12;

    private final SearchService search;
    private final CurrencyService currencies;
    private final ReviewService reviews;

    public HomeController(SearchService search, CurrencyService currencies, ReviewService reviews) {
        this.search = search;
        this.currencies = currencies;
        this.reviews = reviews;
    }

    /**
     * @param currency the currency maxPrice is written in, and the one prices are also shown
     *                 in; blank means the listings' own currencies only
     */
    @GetMapping("/")
    public String home(@RequestParam(required = false) String city,
                       @RequestParam(required = false) Integer guests,
                       @RequestParam(required = false) BigDecimal maxPrice,
                       @RequestParam(required = false) Currency currency,
                       @RequestParam(defaultValue = "0") int page,
                       HttpServletRequest request,
                       Model model) {
        SearchCriteria criteria = new SearchCriteria(city, guests, maxPrice, currencies.orDefault(currency),
                page, PAGE_SIZE);
        SearchResultPage results = currencies.inCurrency(search.search(criteria), currency);

        model.addAttribute("results", results);
        model.addAttribute("ratings", reviews.ratingsFor(results.content().stream().map(PropertySummary::id).toList()));
        model.addAttribute("city", city);
        model.addAttribute("guests", guests);
        model.addAttribute("maxPrice", maxPrice);
        model.addAttribute("currency", currency);
        model.addAttribute("currencies", Currency.values());
        model.addAttribute("previousPage", results.page() > 0 ? pageLink(request, results.page() - 1) : null);
        model.addAttribute("nextPage",
                results.page() + 1 < results.totalPages() ? pageLink(request, results.page() + 1) : null);
        return "home";
    }

    /**
     * This same search, on another page: the filters are kept, only the page changes. A path, not
     * a full URL — behind a proxy (Render's) the app cannot be sure of its own scheme and host.
     */
    private static String pageLink(HttpServletRequest request, int page) {
        return UriComponentsBuilder.fromUriString(PageModelAdvice.pagePath(request))
                .replaceQueryParam("page", page).build().toUriString();
    }
}
