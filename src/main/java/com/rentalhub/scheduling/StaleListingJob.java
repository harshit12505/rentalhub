package com.rentalhub.scheduling;

import com.rentalhub.audit.AuditActor;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.service.PropertyService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Every night, takes listings off the market once their last available day has passed.
 *
 * A host's {@code availableUntil} is the last day a guest can check out (see
 * BookingRules). After it, the listing should stop showing in search, and nothing else
 * would ever switch it off, so this job does.
 *
 * Each listing is deactivated in its own transaction, through PropertyService. So:
 * <ul>
 *   <li>caches and search pages are invalidated, as for any edit;</li>
 *   <li>the audit trail records the change as made by {@code system:stale-listing-job};</li>
 *   <li>one listing that fails (its host saving an edit at that very moment, say) is
 *       logged and simply picked up by the next run, without undoing the others.</li>
 * </ul>
 *
 * One app instance runs it. With several, each would run it: harmless here, since
 * deactivating twice changes nothing, but wasteful. A shared lock (ShedLock, for
 * example) would make one instance do the work.
 */
@Slf4j
@Component
public class StaleListingJob {

    public static final String NAME = "stale-listing-job";

    private final PropertyRepository properties;
    private final PropertyService propertyService;
    private final Clock clock;

    StaleListingJob(PropertyRepository properties, PropertyService propertyService, Clock clock) {
        this.properties = properties;
        this.propertyService = propertyService;
        this.clock = clock;
    }

    /** The schedule: rentalhub.jobs.stale-listings.cron and .zone in application.yml. */
    @Scheduled(cron = "${rentalhub.jobs.stale-listings.cron}", zone = "${rentalhub.jobs.stale-listings.zone}")
    void runOnSchedule() {
        deactivateExpiredListings();
    }

    /**
     * One run of the job. Public, and separate from the scheduled method, so a test (or
     * one day an admin button) can run it directly, without waiting for the clock.
     */
    public Report deactivateExpiredListings() {
        LocalDate today = LocalDate.now(clock);
        long started = System.nanoTime();
        List<Long> deactivated = new ArrayList<>();
        List<Long> failed = new ArrayList<>();

        // Everything this run changes is recorded as done by the job, and everything it
        // logs is tagged job=stale-listing-job.
        try (AuditActor.Scope actor = AuditActor.as(AuditActor.system(NAME));
             MDC.MDCCloseable job = MDC.putCloseable("job", NAME)) {
            for (long id : properties.findActiveIdsAvailableUntilBefore(today)) {
                try {
                    if (propertyService.deactivate(id, "availability-ended")) {
                        deactivated.add(id);
                    }
                } catch (RuntimeException e) {
                    failed.add(id);
                    log.atWarn().setMessage("job.staleListings.listingFailed")
                            .addKeyValue("propertyId", id)
                            .addKeyValue("error", e.getMessage())
                            .log();
                }
            }
            log.atInfo().setMessage("job.staleListings.finished")
                    .addKeyValue("availableUntilBefore", today)
                    .addKeyValue("deactivated", deactivated.size())
                    .addKeyValue("failed", failed.size())
                    .addKeyValue("propertyIds", deactivated)
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log();
        }
        return new Report(today, List.copyOf(deactivated), List.copyOf(failed));
    }

    /**
     * What one run did.
     *
     * @param availableUntilBefore the cut-off: listings whose last day was before this date
     * @param deactivated          the listings taken off the market
     * @param failed               the listings that could not be; the next run tries again
     */
    public record Report(LocalDate availableUntilBefore, List<Long> deactivated, List<Long> failed) {
    }
}
