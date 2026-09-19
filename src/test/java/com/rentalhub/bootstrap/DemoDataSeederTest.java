package com.rentalhub.bootstrap;

import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.PaymentStatus;
import com.rentalhub.domain.model.enums.UserRole;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.BookingView;
import com.rentalhub.service.BookingService;
import com.rentalhub.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo world, seeded for real into the test database (the shared test context switches the
 * start-up run off, so each test here seeds when it chooses to).
 */
class DemoDataSeederTest extends IntegrationTest {

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private UserRepository users;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("an empty database gets users, listings of every type, stays, reviews and favourites, all through the real rules")
    void seedsAnEmptyDatabase() {
        DemoDataSeeder.Report report = seeder.seedIfEmpty().orElseThrow();

        assertThat(report).isEqualTo(new DemoDataSeeder.Report(8, 16, 13, 8, 8));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT property_type) FROM properties", Integer.class))
                .as("all four property types").isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT currency) FROM properties", Integer.class))
                .as("prices in every currency the app supports").isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM properties WHERE active", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE status = 'CONFIRMED' AND payment_status = 'PAID' AND payment_provider = 'SIMULATED'",
                Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bookings b JOIN properties p ON p.id = b.property_id WHERE b.guests > p.max_guests",
                Integer.class)).as("no stay has more guests than its listing sleeps").isZero();
    }

    @Test
    @DisplayName("dates count from today: some stays have ended (and have reviews), some are still to come")
    void datesAreRelativeToToday() {
        seeder.seedIfEmpty();
        LocalDate today = LocalDate.now();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE check_out <= ?", Integer.class, today)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE check_in > ?", Integer.class, today)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isEqualTo(8);
    }

    @Test
    @DisplayName("the site opens full: the home page shows the demo listings with their ratings")
    void homePageIsNotBlank() throws Exception {
        seeder.seedIfEmpty();

        assertThat(page("/")).contains("16 place(s) found", "Page 1 of 2", "(1 review(s))");
        assertThat(page("/?city=Goa")).contains("Quiet garden villa", "4.5 (2 review(s))");
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("it never runs twice, and never touches a database that already has a user")
    void onlyAnEmptyDatabase() {
        users.save(new User("Someone Real", "someone@example.com", UserRole.GUEST));

        assertThat(seeder.seedIfEmpty()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM properties", Integer.class)).isZero();

        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        assertThat(seeder.seedIfEmpty()).isPresent();
        assertThat(seeder.seedIfEmpty()).as("the second run finds data").isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(8);
    }

    @Test
    @DisplayName("the audit history says the demo seeder did it")
    void auditedAsTheSeeder() {
        seeder.seedIfEmpty();

        assertThat(jdbc.queryForList("SELECT DISTINCT changed_by FROM revinfo", String.class))
                .containsExactly("system:demo-seeder");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM properties_aud", Integer.class)).isEqualTo(16);
    }

    @Test
    @DisplayName("a seeded upcoming booking can be cancelled by its guest and is refunded in full, as a real one would be")
    void seededBookingsBehaveLikeRealOnes() {
        seeder.seedIfEmpty();
        long raviId = users.findByEmail("ravi.kumar@example.com").orElseThrow().getId();
        List<BookingView> ravis = bookingService.forGuest(raviId);
        BookingView upcoming = ravis.stream().filter(b -> b.checkIn().isAfter(LocalDate.now())).findFirst().orElseThrow();

        BookingView cancelled = bookingService.cancel(upcoming.id(), raviId);

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
    }
}
