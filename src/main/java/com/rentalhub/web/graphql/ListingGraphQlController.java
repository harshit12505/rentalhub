package com.rentalhub.web.graphql;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.PropertyImageRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.dto.ReviewView;
import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.dto.SearchResultPage;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.service.ReviewService;
import com.rentalhub.service.SearchService;
import org.springframework.context.MessageSource;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Listings over GraphQL: search and detail.
 *
 * The same services as REST, so the same caches, the same rules and the same currency
 * handling. What GraphQL adds is that the client picks the fields — and that picking a field on
 * every item of a list is where the <b>N+1 problem</b> lives. Ask for twenty search results and
 * each one's host, and a naive resolver runs one query for the page and then one per listing
 * for its host: 21 queries, and 41 once photos are asked for too. The two
 * {@code @BatchMapping} methods below are called once per page with <em>all</em> the listings
 * on it, and answer each with a single {@code IN (...)} query, so a page costs the same few
 * queries however many listings it holds.
 */
@Controller
public class ListingGraphQlController {

    private final PropertyService properties;
    private final SearchService search;
    private final CurrencyService currencies;
    private final ReviewService reviews;
    private final PropertyRepository propertyRepository;
    private final PropertyImageRepository images;
    private final MessageSource messages;

    public ListingGraphQlController(PropertyService properties,
                                    SearchService search,
                                    CurrencyService currencies,
                                    ReviewService reviews,
                                    PropertyRepository propertyRepository,
                                    PropertyImageRepository images,
                                    MessageSource messages) {
        this.properties = properties;
        this.search = search;
        this.currencies = currencies;
        this.reviews = reviews;
        this.propertyRepository = propertyRepository;
        this.images = images;
        this.messages = messages;
    }

    /** Null, as GraphQL convention has it, when there is no such listing. */
    @QueryMapping
    public PropertyView property(@Argument long id, @Argument Currency currency) {
        try {
            return currencies.inCurrency(properties.getListing(id), currency);
        } catch (ResourceNotFoundException missing) {
            return null;
        }
    }

    @QueryMapping
    public SearchResultPage searchProperties(@Argument SearchFilter filter, @Argument int page, @Argument int size) {
        SearchFilter given = filter == null ? new SearchFilter(null, null, null, null) : filter;
        SearchCriteria criteria = new SearchCriteria(given.city(), given.guests(), given.maxPrice(),
                currencies.orDefault(given.currency()), page, size);
        return currencies.inCurrency(search.search(criteria), given.currency());
    }

    @SchemaMapping(typeName = "ListingCard", field = "typeLabel")
    public String cardTypeLabel(PropertySummary card, Locale locale) {
        return messages.getMessage("property.type." + card.type(), null, locale);
    }

    @SchemaMapping(typeName = "Listing", field = "typeLabel")
    public String typeLabel(PropertyView listing, Locale locale) {
        return messages.getMessage("property.type." + listing.type(), null, locale);
    }

    /**
     * A listing's own fields, whatever its type, with labels from the same message keys the
     * validation errors use. No branch on the property type: the names come from the listing.
     */
    @SchemaMapping(typeName = "Listing")
    public List<AttributeView> attributes(PropertyView listing, Locale locale) {
        List<AttributeView> attributes = new ArrayList<>();
        listing.attributes().forEach((name, value) -> attributes.add(new AttributeView(
                name,
                messages.getMessage("property.attribute." + name, null, name, locale),
                textOf(value),
                valueLabel(name, value, locale))));
        return attributes;
    }

    @SchemaMapping(typeName = "Listing")
    public List<ReviewView> reviews(PropertyView listing) {
        return reviews.forListing(listing.id());
    }

    /** Every card's host, for the whole page, in one query. */
    @BatchMapping(typeName = "ListingCard")
    public List<PropertyView.Host> host(List<PropertySummary> cards) {
        Map<Long, PropertyView.Host> byListing = new HashMap<>();
        propertyRepository.findHostsOf(idsOf(cards)).forEach(row ->
                byListing.put(row.getPropertyId(), new PropertyView.Host(row.getHostId(), row.getFullName())));
        return cards.stream().map(card -> byListing.get(card.id())).toList();
    }

    /** Every card's photos, for the whole page, in one query. */
    @BatchMapping(typeName = "ListingCard")
    public List<List<PropertyView.Image>> images(List<PropertySummary> cards) {
        Map<Long, List<PropertyView.Image>> byListing = new LinkedHashMap<>();
        images.findImagesOf(idsOf(cards)).forEach(row -> byListing
                .computeIfAbsent(row.getPropertyId(), id -> new ArrayList<>())
                .add(new PropertyView.Image(row.getId(), row.getUrl(), row.getSortOrder())));
        return cards.stream().map(card -> byListing.getOrDefault(card.id(), List.of())).toList();
    }

    private static List<Long> idsOf(List<PropertySummary> cards) {
        return cards.stream().map(PropertySummary::id).toList();
    }

    private static String textOf(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }

    /** Yes/No for a flag, the translated name of a choice, and nothing for a plain number. */
    private String valueLabel(String name, Object value, Locale locale) {
        if (value instanceof Boolean flag) {
            return messages.getMessage("attribute.value." + flag, null, locale);
        }
        if (value == null) {
            return null;
        }
        return messages.getMessage("property.attribute." + name + "." + value, null, null, locale);
    }

    /** The optional filter of {@code searchProperties}; every part of it may be left out. */
    public record SearchFilter(String city, Integer guests, BigDecimal maxPrice, Currency currency) {
    }

    /** One type-specific field of a listing, as the schema's Attribute type shows it. */
    public record AttributeView(String name, String label, String value, String valueLabel) {
    }
}
