package com.rentalhub.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI tuning, bound from {@code rentalhub.ai.*} in application.yml.
 *
 * @param candidates         how many listings the vector search brings back before the SQL
 *                           filters run. Deliberately wider than the answer needs, because
 *                           the filters drop some of them
 * @param maxRecommendations the most listings one answer may mention
 * @param indexJob           the backfill job's settings (see scheduling/EmbeddingIndexJob)
 */
@ConfigurationProperties("rentalhub.ai")
public record AiSettings(
        @DefaultValue("50") int candidates,
        @DefaultValue("5") int maxRecommendations,
        @DefaultValue IndexJob indexJob) {

    /**
     * @param batchSize how many listings one run of the job embeds. Small, because the free
     *                  tier allows only a handful of calls a minute
     */
    public record IndexJob(@DefaultValue("20") int batchSize) {
    }
}
