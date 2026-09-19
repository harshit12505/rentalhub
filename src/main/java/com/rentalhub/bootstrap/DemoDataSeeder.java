package com.rentalhub.bootstrap;

import com.rentalhub.audit.AuditActor;
import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.domain.model.enums.UserRole;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.ReviewRequest;
import com.rentalhub.service.FavoriteService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.service.ReviewService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fills an empty database with a small, believable world, so the site never opens blank —
 * on your machine after {@code docker compose down -v}, or on a fresh deployment.
 *
 * What it creates is data, not code: {@code demo/demo-data.json} (users, listings of every
 * type, stays, reviews, favourites). Its dates are counted in days from today, so an "upcoming"
 * booking is still upcoming whenever the app is deployed.
 *
 * The rules it keeps:
 * <ul>
 *   <li><b>Only an empty database.</b> If there is any user at all, it does nothing, so it can
 *       never mix demo data into real data, or seed twice.</li>
 *   <li><b>All or nothing.</b> Everything happens in one transaction. If anything fails, nothing
 *       is left half-made, the reason is logged, and the app still starts (a missing demo is
 *       not a reason to be down); the next start tries again.</li>
 *   <li><b>The same rules as everyone else.</b> Listings go through PropertyService (the
 *       factory's checks, bean validation, cache events, the AI index), reviews through
 *       ReviewService (only after a finished stay), favourites through FavoriteService.</li>
 *   <li><b>One exception: bookings.</b> Past stays are the point (a review needs one), and the
 *       booking service rightly refuses past dates. And paying at startup would make starting
 *       depend on Stripe. So bookings are recorded directly, already paid through the payment
 *       simulator — which stays available even with a Stripe key, so cancelling one later still
 *       refunds it.</li>
 *   <li><b>Recorded as such.</b> The audit history names {@code system:demo-seeder}.</li>
 * </ul>
 * {@code rentalhub.demo-data.enabled=false} (env {@code DEMO_DATA_ENABLED}) switches it off.
 */
@Slf4j
@Component
public class DemoDataSeeder implements ApplicationRunner {

    static final String NAME = "demo-seeder";
    static final String DATA_FILE = "demo/demo-data.json";

    private final boolean enabled;
    private final UserRepository users;
    private final PropertyRepository properties;
    private final BookingRepository bookings;
    private final PropertyService propertyService;
    private final ReviewService reviews;
    private final FavoriteService favorites;
    private final JsonMapper json;
    private final Validator validator;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DemoDataSeeder(@Value("${rentalhub.demo-data.enabled:true}") boolean enabled,
                          UserRepository users,
                          PropertyRepository properties,
                          BookingRepository bookings,
                          PropertyService propertyService,
                          ReviewService reviews,
                          FavoriteService favorites,
                          JsonMapper json,
                          Validator validator,
                          PlatformTransactionManager transactions,
                          Clock clock) {
        this.enabled = enabled;
        this.users = users;
        this.properties = properties;
        this.bookings = bookings;
        this.propertyService = propertyService;
        this.reviews = reviews;
        this.favorites = favorites;
        this.json = json;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.atInfo().setMessage("demo.skipped").addKeyValue("reason", "switched off").log();
            return;
        }
        try {
            seedIfEmpty();
        } catch (RuntimeException e) {
            log.atError().setMessage("demo.failed")
                    .addKeyValue("error", e.getClass().getName())
                    .setCause(e)
                    .log();
        }
    }

    /**
     * Seeds, if the database has no users yet.
     *
     * @return what was created, or empty if the database already had data
     */
    public Optional<Report> seedIfEmpty() {
        try (AuditActor.Scope actor = AuditActor.as(AuditActor.system(NAME));
             MDC.MDCCloseable job = MDC.putCloseable("job", NAME)) {
            long started = System.nanoTime();
            Optional<Report> report = transaction.execute(status -> {
                if (users.count() > 0) {
                    return Optional.<Report>empty();
                }
                return Optional.of(seed(load()));
            });
            if (report == null || report.isEmpty()) {
                log.atInfo().setMessage("demo.skipped").addKeyValue("reason", "database not empty").log();
                return Optional.empty();
            }
            log.atInfo().setMessage("demo.seeded")
                    .addKeyValue("users", report.get().users())
                    .addKeyValue("listings", report.get().listings())
                    .addKeyValue("bookings", report.get().bookings())
                    .addKeyValue("reviews", report.get().reviews())
                    .addKeyValue("favourites", report.get().favourites())
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log();
            return report;
        }
    }

    private Report seed(DemoData data) {
        LocalDate today = LocalDate.now(clock);

        Map<String, User> userByKey = new HashMap<>();
        for (DemoData.UserRow row : data.users()) {
            userByKey.put(row.key(), users.save(new User(row.fullName(), row.email(), row.role())));
        }

        Map<String, Long> listingByKey = new HashMap<>();
        for (DemoData.Listing row : data.listings()) {
            requireValid(row.key(), row.listing());
            listingByKey.put(row.key(), propertyService.create(row.listing(), lookUp(userByKey, row.host()).getId()).id());
        }

        int reviewCount = 0;
        for (int i = 0; i < data.stays().size(); i++) {
            DemoData.Stay stay = data.stays().get(i);
            Property property = properties.findById(lookUp(listingByKey, stay.listing())).orElseThrow();
            User guest = lookUp(userByKey, stay.guest());
            LocalDate checkIn = today.plusDays(stay.startsInDays());
            Booking booking = Booking.reserve(property, guest, checkIn, checkIn.plusDays(stay.nights()), stay.guests());
            booking.paymentStarted(PaymentProvider.SIMULATED, "sim_pi_demo_" + (i + 1));
            booking.paid();
            bookings.save(booking);
            if (stay.review() != null) {
                ReviewRequest review = new ReviewRequest();
                review.setRating(stay.review().rating());
                review.setComment(stay.review().comment());
                reviews.create(property.getId(), review, guest.getId());
                reviewCount++;
            }
        }

        for (DemoData.Favourite favourite : data.favourites()) {
            favorites.save(lookUp(listingByKey, favourite.listing()), lookUp(userByKey, favourite.user()).getId());
        }

        return new Report(userByKey.size(), listingByKey.size(), data.stays().size(), reviewCount,
                data.favourites().size());
    }

    /** The same bean validation a listing sent to the API gets; the factory then checks the rest. */
    private void requireValid(String key, PropertyRequest listing) {
        Set<ConstraintViolation<PropertyRequest>> violations = validator.validate(listing);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Demo listing '" + key + "' is invalid: " + violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .collect(Collectors.joining(", ")));
        }
    }

    private DemoData load() {
        try (InputStream in = new ClassPathResource(DATA_FILE).getInputStream()) {
            return json.readValue(in, DemoData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + DATA_FILE, e);
        }
    }

    private static <T> T lookUp(Map<String, T> byKey, String key) {
        T found = byKey.get(key);
        if (found == null) {
            throw new IllegalStateException("Demo data refers to '" + key + "', which it never defines");
        }
        return found;
    }

    /** How much was created. */
    public record Report(int users, int listings, int bookings, int reviews, int favourites) {
    }

    /** The shape of {@code demo/demo-data.json}. Listings are the API's own PropertyRequest. */
    record DemoData(List<UserRow> users, List<Listing> listings, List<Stay> stays, List<Favourite> favourites) {

        record UserRow(String key, String fullName, String email, UserRole role) {
        }

        record Listing(String key, String host, PropertyRequest listing) {
        }

        /** A booking {@code startsInDays} from today (negative: in the past), with an optional review. */
        record Stay(String guest, String listing, int startsInDays, int nights, int guests, Review review) {
        }

        record Review(int rating, String comment) {
        }

        record Favourite(String user, String listing) {
        }
    }
}
