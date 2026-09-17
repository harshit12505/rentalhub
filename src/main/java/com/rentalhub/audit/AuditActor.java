package com.rentalhub.audit;

import java.util.Optional;

/**
 * Who is making the current change, as the audit trail should record it: {@code user:7}
 * for a request made as user 7, {@code system:<job>} for a background job, or
 * {@code anonymous} when nobody said.
 *
 * Kept per thread. Hibernate calls the revision listener on the thread that runs the
 * transaction, and that is the thread which set the actor: the request thread for web
 * calls (see RequestContextFilter), the scheduler thread for jobs. Every setter hands
 * back a {@link Scope} that restores the previous value, so a pooled thread never
 * carries one request's identity into the next.
 */
public final class AuditActor {

    public static final String ANONYMOUS = "anonymous";

    private static final String USER_PREFIX = "user:";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AuditActor() {
    }

    public static String current() {
        String actor = CURRENT.get();
        return actor == null ? ANONYMOUS : actor;
    }

    public static String user(long userId) {
        return USER_PREFIX + userId;
    }

    public static String system(String name) {
        return "system:" + name;
    }

    /** The user id in {@code user:<id>}, or empty for any other actor. */
    public static Optional<Long> userIdOf(String actor) {
        if (actor == null || !actor.startsWith(USER_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(actor.substring(USER_PREFIX.length())));
        } catch (NumberFormatException notAUserId) {
            return Optional.empty();
        }
    }

    /**
     * Makes {@code actor} current until the returned scope is closed:
     * {@code try (var scope = AuditActor.as(AuditActor.system("nightly-job"))) { ... }}.
     */
    public static Scope as(String actor) {
        String previous = CURRENT.get();
        CURRENT.set(actor);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** Closing restores whoever was acting before; it never throws. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
