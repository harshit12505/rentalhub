package com.rentalhub.persistence;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.factory.PropertyFactory;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against a real Postgres. Simply starting proves the
 * two things no unit test can: every Flyway migration runs cleanly, and Hibernate's
 * ddl-auto=validate agrees that every entity (and every Envers history table) matches.
 *
 * {@code @Transactional} rolls every test back, so tests never see each other's rows.
 */
@Transactional
class PersistenceMappingTest extends IntegrationTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired
    private Flyway flyway;

    @Autowired
    private PropertyFactory factory;

    @Autowired
    private PropertyRepository properties;

    @Autowired
    private UserRepository users;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Validator validator;

    @Test
    @DisplayName("Flyway applied every migration, none is pending, and Hibernate validated the schema")
    void schemaIsMigratedAndValid() {
        MigrationInfo[] all = flyway.info().all();
        assertThat(flyway.info().pending()).as("migrations not yet applied").isEmpty();
        assertThat(flyway.info().current().getVersion()).isEqualTo(all[all.length - 1].getVersion());
    }

    @Test
    @DisplayName("every property type survives a round trip through the database as its own subtype")
    void everyTypeRoundTrips() {
        User host = users.save(TestRequests.host());
        List<Property> saved = new ArrayList<>();
        for (PropertyRequest request : TestRequests.oneValidPerType()) {
            saved.add(properties.saveAndFlush(factory.create(request, host)));
        }
        // Forget everything Hibernate has cached, so the reads below really hit the database.
        entityManager.clear();

        for (Property original : saved) {
            Property loaded = properties.findById(original.getId()).orElseThrow();

            assertThat(loaded.getClass()).isEqualTo(original.getClass());
            assertThat(loaded.getVersion()).isZero();
            assertThat(loaded.getPricePerNight()).isEqualByComparingTo(original.getPricePerNight());
            // BigDecimal.equals() also compares scale (14.5 vs 14.50), which is why the
            // project compares money and measurements with compareTo.
            assertThat(loaded.typeAttributes())
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(original.typeAttributes());
        }
    }

    @Test
    @DisplayName("search applies each filter only when it is given, and hides inactive listings")
    void searchFilters() {
        User host = users.save(TestRequests.host());
        properties.save(factory.create(TestRequests.validApartment(), host));   // Chennai, 2500, 2 guests
        properties.save(factory.create(TestRequests.validVilla(), host));       // Goa, 12000, 6 guests
        Property inactive = factory.create(TestRequests.validStudio(), host);   // Chennai, inactive
        inactive.setActive(false);
        properties.saveAndFlush(inactive);

        assertThat(titles(properties.search(null, null, null, FIRST_PAGE).getContent()))
                .containsExactlyInAnyOrder("Test apartment", "Test villa");
        assertThat(titles(properties.search("CHENNAI", null, null, FIRST_PAGE).getContent()))
                .containsExactly("Test apartment");
        assertThat(titles(properties.search(null, 4, null, FIRST_PAGE).getContent()))
                .containsExactly("Test villa");
        assertThat(titles(properties.search(null, null, Map.of(Currency.INR, new BigDecimal("3000")), FIRST_PAGE)
                .getContent()))
                .containsExactly("Test apartment");
        // A currency with no ceiling matches nothing: both listings are priced in rupees.
        assertThat(properties.search(null, null, Map.of(Currency.USD, new BigDecimal("1000000")), FIRST_PAGE)
                .getContent()).isEmpty();
    }

    @Test
    @DisplayName("bean validation messages are read from messages.properties")
    void validationMessagesAreLocalised() {
        PropertyRequest request = TestRequests.validApartment();
        request.setTitle("x".repeat(151));
        request.setCity(" ");

        Map<String, String> messages = validator.validate(request).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));

        assertThat(messages)
                .hasSize(2)
                .containsEntry("title", "Must be at most 150 characters.")
                .containsEntry("city", "This field is required.");
    }

    private static List<String> titles(List<Property> found) {
        return found.stream().map(Property::getTitle).toList();
    }
}
