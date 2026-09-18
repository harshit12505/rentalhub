package com.rentalhub.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The bookkeeping around the vector index, in plain SQL.
 *
 * Two jobs, both of which are a poor fit for JPA:
 * <ul>
 *   <li><b>What has been embedded, and from what text.</b> One upsert per listing, keyed by
 *       the listing's id, and a left join to find listings that have no embedding yet.</li>
 *   <li><b>Arithmetic on the vectors themselves.</b> Postgres can average a set of vectors
 *       and sort by distance from the result, so a user's "taste vector" is computed and used
 *       without ever travelling into Java. See {@link #nearestToFavourites}.</li>
 * </ul>
 * The embeddings themselves are written and read by Spring AI's PgVectorStore; this class
 * only ever joins to its table.
 */
@Component
public class EmbeddingIndexStore {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    EmbeddingIndexStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** The hash of the text this listing was last embedded from, if it ever was. */
    public Optional<String> hashOf(long propertyId) {
        return jdbc.query("SELECT content_hash FROM listing_embeddings WHERE property_id = ?",
                        rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty(), propertyId);
    }

    /** Remembers that this listing is now embedded, as this document, from this text. */
    public void record(long propertyId, UUID documentId, String contentHash) {
        jdbc.update("""
                INSERT INTO listing_embeddings (property_id, document_id, content_hash, embedded_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (property_id) DO UPDATE
                    SET document_id = EXCLUDED.document_id,
                        content_hash = EXCLUDED.content_hash,
                        embedded_at = now()
                """, propertyId, documentId, contentHash);
    }

    /** Forgets a listing, so the next run embeds it again (or, after a delete, leaves it alone). */
    public void forget(long propertyId) {
        jdbc.update("DELETE FROM listing_embeddings WHERE property_id = ?", propertyId);
    }

    /** Active listings with no embedding yet: the backfill job's work list, oldest first. */
    public List<Long> listingsWithoutEmbedding(int limit) {
        return jdbc.queryForList("""
                SELECT p.id FROM properties p
                LEFT JOIN listing_embeddings e ON e.property_id = p.id
                WHERE p.active = true AND e.property_id IS NULL
                ORDER BY p.id
                LIMIT ?
                """, Long.class, limit);
    }

    /**
     * The listings closest to the average of the given ones: "more like these".
     *
     * The average of a set of embeddings is a point in the same space, near everything they
     * have in common, so it stands in for a taste with no model call at all. Postgres computes
     * it and orders by cosine distance from it, so no vector is ever loaded into Java.
     *
     * @return property ids with a score from 0 to 1, closest first, excluding the listings
     *         the taste was built from
     */
    public List<ScoredListing> nearestToFavourites(Collection<Long> favouriteIds, int limit) {
        if (favouriteIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("favourites", favouriteIds)
                .addValue("limit", limit);
        return namedJdbc.query("""
                WITH taste AS (
                    SELECT avg(v.embedding) AS centre
                    FROM vector_store v
                    JOIN listing_embeddings e ON e.document_id = v.id
                    WHERE e.property_id IN (:favourites)
                )
                SELECT e.property_id, 1 - (v.embedding <=> (SELECT centre FROM taste)) AS score
                FROM vector_store v
                JOIN listing_embeddings e ON e.document_id = v.id
                WHERE e.property_id NOT IN (:favourites)
                ORDER BY v.embedding <=> (SELECT centre FROM taste)
                LIMIT :limit
                """, parameters,
                (rs, row) -> new ScoredListing(rs.getLong("property_id"), rs.getDouble("score")));
    }

    /** A listing and how close it came, where 1 is identical and 0 is unrelated. */
    public record ScoredListing(long propertyId, double score) {
    }
}
