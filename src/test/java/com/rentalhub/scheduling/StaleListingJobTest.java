package com.rentalhub.scheduling;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stale-listing job, run by calling its method directly, as a scheduler would, but
 * without waiting for 03:15. (The schedule itself is checked by StaleListingJobScheduleTest.)
 *
 * The listings' last available days are set with plain SQL: the API refuses a date in
 * the past, which is exactly the situation the job exists for.
 */
@ExtendWith(OutputCaptureExtension.class)
class StaleListingJobTest extends IntegrationTest {

    @Autowired
    private StaleListingJob job;

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private UserRepository users;

    private final LocalDate today = LocalDate.now();
    private long hostId;

    @BeforeEach
    void createHost() {
        hostId = users.save(TestRequests.host()).getId();
    }

    @Test
    @DisplayName("deactivates exactly the active listings whose last day has passed, and logs each one")
    void deactivatesExpiredListings(CapturedOutput output) {
        long expired = listingAvailableUntil(today.minusDays(1));
        long endsToday = listingAvailableUntil(today);
        long endsLater = listingAvailableUntil(today.plusDays(10));
        long noEndDate = listingAvailableUntil(null);
        long alreadyInactive = listingAvailableUntil(today.minusDays(3));
        jdbc.update("UPDATE properties SET active = false WHERE id = ?", alreadyInactive);
        // Cache the expired listing's page first, to prove the job's change evicts it.
        assertThat(propertyService.getListing(expired).active()).isTrue();

        StaleListingJob.Report report = job.deactivateExpiredListings();

        assertThat(report.availableUntilBefore()).isEqualTo(today);
        assertThat(report.deactivated()).containsExactly(expired);
        assertThat(report.failed()).isEmpty();
        assertThat(isActive(expired)).isFalse();
        assertThat(isActive(endsToday)).as("its last day is today: still bookable").isTrue();
        assertThat(isActive(endsLater)).isTrue();
        assertThat(isActive(noEndDate)).isTrue();
        assertThat(propertyService.getListing(expired).active()).as("the cached copy followed").isFalse();

        assertThat(output)
                .contains("listing.deactivated propertyId=" + expired)
                .contains("reason=availability-ended")
                .contains("job.staleListings.finished availableUntilBefore=" + today + " deactivated=1 failed=0 propertyIds=[" + expired + "]");
    }

    @Test
    @DisplayName("the change is recorded in the listing's history as made by the job")
    void recordedAsTheJob() {
        long expired = listingAvailableUntil(today.minusDays(1));

        job.deactivateExpiredListings();

        String changedBy = jdbc.queryForObject("""
                SELECT r.changed_by FROM properties_aud a JOIN revinfo r ON r.rev = a.rev
                WHERE a.id = ? AND a.active = false
                """, String.class, expired);
        assertThat(changedBy).isEqualTo("system:stale-listing-job");
    }

    @Test
    @DisplayName("a second run finds nothing left to do")
    void secondRunDoesNothing() {
        listingAvailableUntil(today.minusDays(1));
        job.deactivateExpiredListings();

        assertThat(job.deactivateExpiredListings().deactivated()).isEmpty();
    }

    private long listingAvailableUntil(LocalDate lastDay) {
        long id = propertyService.create(TestRequests.validApartment(), hostId).id();
        jdbc.update("UPDATE properties SET available_until = ? WHERE id = ?", lastDay, id);
        return id;
    }

    private boolean isActive(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM properties WHERE id = ?", Boolean.class, id));
    }
}
