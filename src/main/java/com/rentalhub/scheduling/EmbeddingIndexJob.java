package com.rentalhub.scheduling;

import com.rentalhub.ai.AiAvailability;
import com.rentalhub.ai.AiSettings;
import com.rentalhub.ai.ListingEmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Embeds listings that have no embedding yet.
 *
 * Listings are normally embedded the moment they change (ai/ListingIndexUpdater). This job is
 * for the ones that missed it:
 * <ul>
 *   <li>every listing created while no Gemini key was configured, which is all of them until
 *       the day a key is added;</li>
 *   <li>listings whose embedding call failed, for example because the free tier's rate limit
 *       had been reached.</li>
 * </ul>
 * It works in small batches for that same reason: the free tier allows a few calls a minute,
 * and a backlog is better cleared slowly than not at all.
 */
@Slf4j
@Component
public class EmbeddingIndexJob {

    public static final String NAME = "embedding-index-job";

    private final ListingEmbeddingService embeddings;
    private final AiAvailability availability;
    private final AiSettings settings;

    EmbeddingIndexJob(ListingEmbeddingService embeddings, AiAvailability availability, AiSettings settings) {
        this.embeddings = embeddings;
        this.availability = availability;
        this.settings = settings;
    }

    /** The schedule: rentalhub.ai.index-job.cron in application.yml. */
    @Scheduled(cron = "${rentalhub.ai.index-job.cron}")
    void runOnSchedule() {
        indexMissingListings();
    }

    /** One run. Public, so a test can call it without waiting for the clock. */
    public List<Long> indexMissingListings() {
        if (!availability.canSearch()) {
            return List.of();
        }
        try (MDC.MDCCloseable job = MDC.putCloseable("job", NAME)) {
            List<Long> embedded = embeddings.indexMissing(settings.indexJob().batchSize());
            if (!embedded.isEmpty()) {
                log.atInfo().setMessage("job.embeddingIndex.finished")
                        .addKeyValue("embedded", embedded.size())
                        .addKeyValue("propertyIds", embedded)
                        .log();
            }
            return embedded;
        }
    }
}
