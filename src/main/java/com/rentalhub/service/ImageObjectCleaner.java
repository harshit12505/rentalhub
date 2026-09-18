package com.rentalhub.service;

import com.rentalhub.storage.ImageStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Deletes photo files from storage once the rows that pointed at them are really gone.
 *
 * After commit, and outside any transaction, like every other external call in this project.
 * It never throws: the listing change has already succeeded, and a file that could not be
 * deleted is only wasted space, so it is logged as {@code image.orphaned} with its key, which
 * is everything needed to remove it by hand.
 */
@Slf4j
@Component
class ImageObjectCleaner {

    private final ImageStore store;

    ImageObjectCleaner(ImageStore store) {
        this.store = store;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onImagesRemoved(ListingImagesRemovedEvent event) {
        for (String key : event.keys()) {
            try {
                store.delete(key);
                log.atDebug().setMessage("image.deleted").addKeyValue("key", key).log();
            } catch (RuntimeException failed) {
                log.atWarn().setMessage("image.orphaned")
                        .addKeyValue("key", key)
                        .addKeyValue("error", failed.getMessage())
                        .log();
            }
        }
    }
}
