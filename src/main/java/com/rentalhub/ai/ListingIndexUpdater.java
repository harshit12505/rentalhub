package com.rentalhub.ai;

import com.rentalhub.service.PropertyChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps the vector index in step with the listings.
 *
 * It listens for the event the caches listen for, and for the same two reasons: only after the
 * change has committed is there anything true to index, and embedding is a network call, which
 * must happen outside the transaction.
 *
 * It never throws. The listing is already saved, and a failed embedding must not turn a
 * request that succeeded into an error. Whatever fails here is picked up later by
 * EmbeddingIndexJob, which sweeps for listings that have no embedding.
 */
@Slf4j
@Component
class ListingIndexUpdater {

    private final ListingEmbeddingService embeddings;

    ListingIndexUpdater(ListingEmbeddingService embeddings) {
        this.embeddings = embeddings;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPropertyChanged(PropertyChangedEvent event) {
        try {
            if (event.currentCity() == null) {
                embeddings.remove(event.propertyId());
            } else {
                embeddings.index(event.propertyId());
            }
        } catch (RuntimeException e) {
            log.atWarn().setMessage("ai.index.failed")
                    .addKeyValue("propertyId", event.propertyId())
                    .addKeyValue("error", e.getMessage())
                    .addKeyValue("next", "the index job will pick it up")
                    .log();
        }
    }
}
