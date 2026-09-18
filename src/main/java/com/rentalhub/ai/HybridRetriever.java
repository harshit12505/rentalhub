package com.rentalhub.ai;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.repository.PropertyImageRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.service.PriceCeilings;
import com.rentalhub.service.PropertyViews;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds listings for a question, using meaning and facts together.
 *
 * <b>Why hybrid.</b> A vector search alone answers "what sounds like this?" from a snapshot of
 * each listing's words, so it will happily offer a listing that was taken off the market this
 * morning, or one that costs three times the budget, because neither fact is in the text. A
 * SQL search alone answers "what matches these filters?" and has no idea what "somewhere quiet
 * near the beach" means. So each does the half it is good at: the vector search proposes
 * candidates by meaning, the database keeps only the ones that are true right now, and the
 * ranking puts similarity and this guest's own habits together.
 *
 * With no AI key there is no vector search, and the whole thing degrades into the ordinary
 * filtered search, ranked by the profile alone. It still answers; it just stops being clever.
 */
@Slf4j
@Component
public class HybridRetriever {

    /** A question this short is not describing anything: fall back to the guest's taste. */
    private static final int TOO_SHORT_TO_MEAN_ANYTHING = 12;

    /**
     * How much a guest's own habits may move a listing up the list. Small on purpose: the
     * question in front of us beats what they usually do, and a boost should never be able
     * to lift a listing that means nothing like the question above one that does.
     */
    private static final double CITY_THEY_LIKE = 0.05;
    private static final double PRICE_THEY_USUALLY_PAY = 0.05;
    private static final double ROOM_FOR_THEIR_USUAL_PARTY = 0.03;

    /** What a listing scores before boosts when there is no similarity to go on. */
    private static final double NO_SIMILARITY = 0.5;

    private final AiAvailability availability;
    private final AiSettings settings;
    private final ObjectProvider<VectorStore> vectorStore;
    private final EmbeddingIndexStore index;
    private final PropertyRepository properties;
    private final PropertyImageRepository images;
    private final CurrencyService currencies;

    HybridRetriever(AiAvailability availability,
                    AiSettings settings,
                    ObjectProvider<VectorStore> vectorStore,
                    EmbeddingIndexStore index,
                    PropertyRepository properties,
                    PropertyImageRepository images,
                    CurrencyService currencies) {
        this.availability = availability;
        this.settings = settings;
        this.vectorStore = vectorStore;
        this.index = index;
        this.properties = properties;
        this.images = images;
        this.currencies = currencies;
    }

    /**
     * The listings to answer this question with, best first.
     *
     * Not transactional: it may call Gemini to embed the question, and the phase-5 rule that
     * no external call happens inside a transaction has not changed. Each repository call
     * opens its own short one.
     */
    public Retrieval retrieve(ParsedQuery query, PreferenceProfile profile) {
        PriceCeilings ceilings = query.maxPrice() == null
                ? null
                : currencies.ceilings(query.maxPrice(), query.currency());

        Map<Long, Double> similarity = similarities(query, profile);
        boolean semantic = !similarity.isEmpty();

        List<Property> candidates = properties.searchCandidates(
                query.city(), query.guests(),
                ceilings == null ? null : ceilings.byCurrency(),
                semantic ? similarity.keySet() : null,
                profile.userId(),
                settings.candidates());

        Map<Long, String> covers = coverImages(candidates);
        List<Match> matches = candidates.stream()
                .map(listing -> new Match(
                        PropertyViews.toSummary(listing, covers.get(listing.getId())),
                        score(similarity.get(listing.getId()), listing, profile),
                        similarity.get(listing.getId())))
                .sorted(Comparator.comparingDouble(Match::score).reversed())
                .limit(settings.maxRecommendations())
                .toList();

        log.atDebug().setMessage("ai.retrieve")
                .addKeyValue("semantic", semantic)
                .addKeyValue("proposed", similarity.size())
                .addKeyValue("afterFilters", candidates.size())
                .addKeyValue("returned", matches.size())
                .log();
        return new Retrieval(matches, semantic, ceilings != null && !ceilings.complete());
    }

    /**
     * How close each listing is to what was asked, from 1 (the same thing) down to 0.
     *
     * Two ways in: the question's own words, or, when the guest asked for "something like my
     * favourites" or typed too little to mean anything, the average of their saved listings'
     * embeddings, which costs no model call at all (see EmbeddingIndexStore).
     *
     * Empty when there is no AI key, when nothing has been embedded yet, or when the call
     * fails. That is not an error here: the caller then searches without meaning.
     */
    private Map<Long, Double> similarities(ParsedQuery query, PreferenceProfile profile) {
        if (!availability.canSearch()) {
            return Map.of();
        }
        boolean askedForTheirTaste = query.likeFavourites() || query.text().length() < TOO_SHORT_TO_MEAN_ANYTHING;
        if (askedForTheirTaste && !profile.favouriteIds().isEmpty()) {
            Map<Long, Double> scores = new LinkedHashMap<>();
            index.nearestToFavourites(profile.favouriteIds(), settings.candidates())
                    .forEach(scored -> scores.put(scored.propertyId(), scored.score()));
            return scores;
        }
        VectorStore store = vectorStore.getIfAvailable();
        if (store == null || query.text().isBlank()) {
            return Map.of();
        }
        try {
            List<Document> found = store.similaritySearch(SearchRequest.builder()
                    .query(query.text())
                    .topK(settings.candidates())
                    .build());
            Map<Long, Double> scores = new LinkedHashMap<>();
            if (found != null) {
                found.forEach(document -> scores.put(propertyIdOf(document), scoreOf(document)));
            }
            return scores;
        } catch (RuntimeException failed) {
            // Embedding the question is a network call: quota, timeout, a revoked key. The
            // question still deserves an answer, so this is a warning, not a failure.
            log.atWarn().setMessage("ai.search.failed")
                    .addKeyValue("reason", failed.getClass().getSimpleName())
                    .addKeyValue("error", failed.getMessage())
                    .setCause(failed)
                    .log();
            return Map.of();
        }
    }

    /** Similarity, plus a nudge for the things this guest keeps choosing. */
    private double score(Double similarity, Property listing, PreferenceProfile profile) {
        double score = similarity == null ? NO_SIMILARITY : similarity;
        if (profile.cities().contains(listing.getCity())) {
            score += CITY_THEY_LIKE;
        }
        if (withinTheirUsualPrices(listing, profile)) {
            score += PRICE_THEY_USUALLY_PAY;
        }
        if (profile.typicalGuests() != null && listing.getMaxGuests() >= profile.typicalGuests()) {
            score += ROOM_FOR_THEIR_USUAL_PARTY;
        }
        return score;
    }

    /**
     * Is this listing priced in the band they usually save in? Compared in the profile's
     * currency, and only when there is a rate for it: an unconvertible price is no evidence
     * either way, so it simply gets no boost.
     */
    private boolean withinTheirUsualPrices(Property listing, PreferenceProfile profile) {
        if (profile.typicalLow() == null || profile.typicalHigh() == null) {
            return false;
        }
        Optional<BigDecimal> price = currencies
                .convertForDisplay(listing.getPricePerNight(), listing.getCurrency(), profile.currency())
                .map(displayed -> displayed.amount());
        return price.filter(amount -> amount.compareTo(profile.typicalLow()) >= 0
                && amount.compareTo(profile.typicalHigh()) <= 0).isPresent();
    }

    /** Cover images for every candidate in one query, as in SearchService: never one per row. */
    private Map<Long, String> coverImages(List<Property> listings) {
        if (listings.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = listings.stream().map(Property::getId).toList();
        Map<Long, String> covers = new HashMap<>();
        for (PropertyImageRepository.ImageUrl image : images.findImageUrls(ids)) {
            covers.putIfAbsent(image.getPropertyId(), image.getUrl());
        }
        return covers;
    }

    private static long propertyIdOf(Document document) {
        return ((Number) document.getMetadata().get(ListingEmbeddingService.PROPERTY_ID)).longValue();
    }

    private static double scoreOf(Document document) {
        return document.getScore() == null ? NO_SIMILARITY : document.getScore();
    }

    /**
     * One listing to suggest.
     *
     * @param score      what it was ranked on: similarity plus the profile's nudges
     * @param similarity how close it was in meaning, or null when there was no vector search
     */
    public record Match(PropertySummary listing, double score, Double similarity) {
    }

    /**
     * @param semantic                 true when the ranking used meaning, false when the
     *                                 answer is an ordinary filtered search
     * @param exchangeRatesUnavailable true when a budget could only be compared with some
     *                                 currencies, exactly as in phase 5's search
     */
    public record Retrieval(List<Match> matches, boolean semantic, boolean exchangeRatesUnavailable) {
    }
}
