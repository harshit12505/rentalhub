package com.rentalhub.scheduling;

import com.rentalhub.audit.AuditActor;
import com.rentalhub.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Every few minutes, finishes the payments that the normal flow could not.
 *
 * Paying for a booking takes several steps with network calls in between (see
 * PaymentService), and anything can stop between two of them: the provider's answer lost to a
 * timeout, the app restarted mid-payment. Those are exactly the moments a half-made booking
 * could come from, so this job sweeps up after them:
 * <ul>
 *   <li><b>Payments still undecided</b> after {@code stale-after} (10 minutes, far longer than
 *       any live payment takes): it asks the provider what happened, then confirms the booking
 *       or releases its dates.</li>
 *   <li><b>Refunds still owed</b> (a paid booking was cancelled, but the refund failed): it
 *       tries again. Each retry reuses the refund's idempotency key, so the guest can never be
 *       refunded twice.</li>
 * </ul>
 * Each booking is handled on its own, so one failure is logged and retried next run without
 * holding up the rest. Changes are recorded in the audit history as the job's.
 *
 * As with StaleListingJob, several instances would each run it. That would be safe (each step
 * settles a booking only once), just wasteful; a shared lock such as ShedLock would fix it.
 */
@Slf4j
@Component
public class PaymentReconciliationJob {

    public static final String NAME = "payment-reconciliation-job";

    private final PaymentService payments;
    private final Clock clock;
    private final Duration staleAfter;

    PaymentReconciliationJob(PaymentService payments,
                             Clock clock,
                             @Value("${rentalhub.jobs.payment-reconciliation.stale-after}") Duration staleAfter) {
        this.payments = payments;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    /** The schedule: rentalhub.jobs.payment-reconciliation.cron in application.yml. */
    @Scheduled(cron = "${rentalhub.jobs.payment-reconciliation.cron}")
    void runOnSchedule() {
        reconcile();
    }

    /** One run of the job. Public, so a test can run it without waiting for the clock. */
    public Report reconcile() {
        Instant cutoff = clock.instant().minus(staleAfter);
        long started = System.nanoTime();
        List<Long> confirmed = new ArrayList<>();
        List<Long> released = new ArrayList<>();
        List<Long> stillPending = new ArrayList<>();
        List<Long> refunded = new ArrayList<>();
        List<Long> refundsStillOwed = new ArrayList<>();
        List<Long> failed = new ArrayList<>();

        try (AuditActor.Scope actor = AuditActor.as(AuditActor.system(NAME));
             MDC.MDCCloseable job = MDC.putCloseable("job", NAME)) {
            for (long bookingId : payments.unsettledPayments(cutoff)) {
                try {
                    switch (payments.settle(bookingId)) {
                        case CONFIRMED -> confirmed.add(bookingId);
                        case RELEASED -> released.add(bookingId);
                        case STILL_PENDING -> stillPending.add(bookingId);
                        case ALREADY_SETTLED -> {
                            // Settled by someone else between the query and now: nothing to do.
                        }
                    }
                } catch (RuntimeException e) {
                    failed.add(bookingId);
                    logFailure(bookingId, "settle", e);
                }
            }
            for (long bookingId : payments.refundsOwed()) {
                try {
                    (payments.retryRefund(bookingId) ? refunded : refundsStillOwed).add(bookingId);
                } catch (RuntimeException e) {
                    failed.add(bookingId);
                    logFailure(bookingId, "refund", e);
                }
            }

            // Quiet when there was nothing to do: this runs every few minutes.
            boolean didSomething = confirmed.size() + released.size() + stillPending.size() + refunded.size()
                    + refundsStillOwed.size() + failed.size() > 0;
            LoggingEventBuilder event = didSomething ? log.atInfo() : log.atDebug();
            event.setMessage("job.paymentReconciliation.finished")
                    .addKeyValue("createdBefore", cutoff)
                    .addKeyValue("confirmed", confirmed)
                    .addKeyValue("released", released)
                    .addKeyValue("stillPending", stillPending)
                    .addKeyValue("refunded", refunded)
                    .addKeyValue("refundsStillOwed", refundsStillOwed)
                    .addKeyValue("failed", failed)
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log();
        }
        return new Report(List.copyOf(confirmed), List.copyOf(released), List.copyOf(stillPending),
                List.copyOf(refunded), List.copyOf(refundsStillOwed), List.copyOf(failed));
    }

    private static void logFailure(long bookingId, String step, RuntimeException e) {
        log.atWarn().setMessage("job.paymentReconciliation.bookingFailed")
                .addKeyValue("bookingId", bookingId)
                .addKeyValue("step", step)
                .addKeyValue("error", e.getMessage())
                .log();
    }

    /**
     * What one run did, by booking id.
     *
     * @param confirmed        undecided payments that had succeeded: now CONFIRMED
     * @param released         undecided payments that had not: now CANCELLED, dates free
     * @param stillPending     the provider doesn't know yet; the next run asks again
     * @param refunded         refunds owed that went through this time
     * @param refundsStillOwed refunds that failed again; the next run tries again
     * @param failed           bookings this run could not process at all (see the log)
     */
    public record Report(List<Long> confirmed, List<Long> released, List<Long> stillPending,
                         List<Long> refunded, List<Long> refundsStillOwed, List<Long> failed) {
    }
}
