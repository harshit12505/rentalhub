package com.rentalhub.ai;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.repository.PropertyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps each listing's meaning in the vector index.
 *
 * An <b>embedding</b> is a list of numbers standing for the meaning of a piece of text: texts
 * that mean similar things get vectors that sit close together. Every listing is turned into
 * one short description (see {@link #textFor}) and embedded, so "somewhere quiet by the sea"
 * can find a listing that uses none of those words.
 *
 * <b>The hash is the important part.</b> Embedding costs a call to Gemini, and the free tier
 * allows only a handful a minute. So the text is hashed, the hash is kept beside the
 * embedding, and a listing is re-embedded only when that hash changes. Saving a listing
 * unchanged, or changing only its availability date, costs nothing.
 *
 * Nothing here runs inside a database transaction: embedding is a network call, and the
 * project's rule is that those never happen with a transaction open.
 */
@Slf4j
@Service
public class ListingEmbeddingService {

    /** The metadata key that links a document in the vector store back to its listing. */
    public static final String PROPERTY_ID = "propertyId";

    private final ObjectProvider<VectorStore> vectorStore;
    private final EmbeddingIndexStore index;
    private final PropertyRepository properties;
    private final AiAvailability availability;

    ListingEmbeddingService(ObjectProvider<VectorStore> vectorStore,
                            EmbeddingIndexStore index,
                            PropertyRepository properties,
                            AiAvailability availability) {
        this.vectorStore = vectorStore;
        this.index = index;
        this.properties = properties;
        this.availability = availability;
    }

    /**
     * Embeds one listing, unless the text it would embed is already the indexed one.
     *
     * @return true if it really called the model
     */
    public boolean index(long propertyId) {
        if (!availability.canSearch()) {
            return false;
        }
        Optional<Property> found = properties.findById(propertyId);
        if (found.isEmpty() || !found.get().isActive()) {
            // Gone, or off the market: either way it must not turn up in a recommendation.
            remove(propertyId);
            return false;
        }
        Property property = found.get();
        String text = textFor(property);
        String hash = hashOf(text);
        if (index.hashOf(propertyId).filter(hash::equals).isPresent()) {
            log.atDebug().setMessage("ai.listing.unchanged").addKeyValue("propertyId", propertyId).log();
            return false;
        }

        UUID documentId = documentIdFor(propertyId);
        VectorStore store = vectorStore.getObject();
        // Replace, don't add: a listing has exactly one document, under an id derived from its
        // own, so an edit can never leave two versions of it in the index.
        store.delete(List.of(documentId.toString()));
        store.add(List.of(Document.builder()
                .id(documentId.toString())
                .text(text)
                .metadata(Map.of(PROPERTY_ID, propertyId,
                        "city", property.getCity(),
                        "type", property.getType().name()))
                .build()));
        index.record(propertyId, documentId, hash);

        log.atInfo().setMessage("ai.listing.embedded")
                .addKeyValue("propertyId", propertyId)
                .addKeyValue("characters", text.length())
                .log();
        return true;
    }

    /** Takes a listing out of the index: deleted, or no longer on the market. */
    public void remove(long propertyId) {
        if (!availability.canSearch()) {
            return;
        }
        vectorStore.getObject().delete(List.of(documentIdFor(propertyId).toString()));
        index.forget(propertyId);
        log.atDebug().setMessage("ai.listing.removed").addKeyValue("propertyId", propertyId).log();
    }

    /**
     * Embeds up to {@code batchSize} listings that have no embedding yet: the backfill path,
     * for listings created while no key was set, and for embedding calls that failed.
     *
     * @return the listings it embedded
     */
    public List<Long> indexMissing(int batchSize) {
        if (!availability.canSearch()) {
            return List.of();
        }
        List<Long> embedded = new ArrayList<>();
        for (long propertyId : index.listingsWithoutEmbedding(batchSize)) {
            try {
                if (index(propertyId)) {
                    embedded.add(propertyId);
                }
            } catch (RuntimeException failed) {
                // One listing at a time, like every other job here: a rate limit or a bad key
                // on this one must not abandon the rest of the batch, and the ones left
                // unembedded are simply still on the list next time.
                log.atWarn().setMessage("ai.listing.embedFailed")
                        .addKeyValue("propertyId", propertyId)
                        .addKeyValue("error", failed.getMessage())
                        .log();
            }
        }
        return List.copyOf(embedded);
    }

    /**
     * What a listing says, as one piece of text for the model: what it is, where it is, what
     * the host wrote, how many it sleeps, what it costs, and its own type's features.
     *
     * The price and the capacity are in there on purpose (the spec asks for them), so "cheap
     * cabin for two" is partly answered by meaning alone. The hard filtering still happens in
     * SQL afterwards, because a number inside a sentence is a hint, not a promise.
     */
    static String textFor(Property property) {
        StringBuilder text = new StringBuilder()
                .append(property.getType().name().toLowerCase(Locale.ROOT)).append(" in ")
                .append(property.getCity()).append(", ").append(property.getCountry()).append(". ")
                .append(property.getTitle()).append(". ")
                .append(property.getDescription()).append(" ")
                .append("Sleeps ").append(property.getMaxGuests())
                .append(", ").append(property.getBedrooms()).append(" bedrooms")
                .append(", ").append(property.getBathrooms()).append(" bathrooms. ")
                // Rounded to the currency's own decimals, not printed as stored: the same
                // price arrives as 12000.00 from the factory and 12000.0000 from NUMERIC(19,4),
                // and a text that changed with that would re-embed the listing for nothing.
                .append(property.getCurrency().round(property.getPricePerNight()).toPlainString()).append(" ")
                .append(property.getCurrency().name()).append(" per night.");
        property.typeAttributes().forEach((name, value) -> {
            if (value != null) {
                text.append(' ').append(name).append(": ").append(value).append('.');
            }
        });
        return text.toString();
    }

    /** SHA-256 of the text, in hex: 64 characters, which is the width of the column holding it. */
    static String hashOf(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every JVM has SHA-256", impossible);
        }
    }

    /**
     * A listing's document id: always the same id for the same listing, so replacing an
     * embedding needs no lookup, and an interrupted index can never leave two of them.
     */
    static UUID documentIdFor(long propertyId) {
        return UUID.nameUUIDFromBytes(("rentalhub-listing-" + propertyId).getBytes(StandardCharsets.UTF_8));
    }
}
