package com.rentalhub.factory;

import com.rentalhub.domain.model.Apartment;
import com.rentalhub.domain.model.Cabin;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.CreatePropertyRequest;
import com.rentalhub.exception.PropertyValidationException;
import com.rentalhub.factory.impl.ApartmentCreator;
import com.rentalhub.factory.impl.VillaCreator;
import com.rentalhub.support.TestMessages;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for the factory and every creator. No database and no web server: a tiny
 * Spring context that scans only the factory package, so the test discovers
 * creators exactly the way the application does, including any added later.
 */
@SpringJUnitConfig(PropertyFactoryTest.FactoryOnly.class)
class PropertyFactoryTest {

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = PropertyFactory.class)
    static class FactoryOnly {
    }

    @Autowired
    private PropertyFactory factory;

    @Autowired
    private List<PropertyCreator> creators;

    private final User host = TestRequests.host();

    // ------------------------------------------------------------------ wiring

    @Test
    @DisplayName("every property type has a creator")
    void everyTypeHasACreator() {
        assertThat(factory.supportedTypes()).containsExactlyInAnyOrder(PropertyType.values());
    }

    @Test
    @DisplayName("the factory refuses to start if a property type has no creator")
    void missingCreatorFailsAtStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new PropertyFactory(List.of(new ApartmentCreator())))
                .withMessageContaining("VILLA");
    }

    @Test
    @DisplayName("the factory refuses two creators for the same type")
    void duplicateCreatorFailsAtStartup() {
        List<PropertyCreator> withDuplicate = new ArrayList<>(creators);
        withDuplicate.add(new VillaCreator());

        assertThatIllegalStateException()
                .isThrownBy(() -> new PropertyFactory(withDuplicate))
                .withMessageContaining("Two PropertyCreators registered for VILLA");
    }

    @Test
    @DisplayName("each entity exposes exactly the attributes its creator declares, and every label exists")
    void entityAttributesMatchCreatorSpecs() throws Exception {
        for (PropertyCreator creator : creators) {
            Property blank = creator.entityClass().getDeclaredConstructor().newInstance();
            List<String> declared = creator.attributeSpecs().stream().map(AttributeSpec::name).toList();

            assertThat(blank.getType()).as("type of %s", blank.getClass()).isEqualTo(creator.supportedType());
            assertThat(blank.typeAttributes().keySet())
                    .as("typeAttributes() of %s", blank.getClass().getSimpleName())
                    .containsExactlyElementsOf(declared);

            assertThat(TestMessages.english("property.type." + creator.supportedType().name())).isNotBlank();
            for (AttributeSpec spec : creator.attributeSpecs()) {
                assertThat(TestMessages.english(spec.labelKey())).isNotBlank();
                for (String choice : spec.choices()) {
                    assertThat(TestMessages.english(spec.choiceLabelKey(choice))).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("the test fixtures cover every property type")
    void fixturesCoverEveryType() {
        assertThat(TestRequests.oneValidPerType())
                .extracting(CreatePropertyRequest::getType)
                .containsExactlyInAnyOrder(PropertyType.values());
    }

    // ---------------------------------------------------------------- building

    @Test
    @DisplayName("every valid request builds its own subtype")
    void everyValidRequestBuilds() {
        for (CreatePropertyRequest request : TestRequests.oneValidPerType()) {
            Property property = factory.create(request, host);

            assertThat(property.getType()).isEqualTo(request.getType());
            assertThat(property.isActive()).isTrue();
        }
    }

    @Test
    @DisplayName("shared fields are copied onto the entity")
    void copiesSharedFields() {
        Apartment apartment = (Apartment) factory.create(TestRequests.validApartment(), host);

        assertThat(apartment.getHost()).isSameAs(host);
        assertThat(apartment.getCity()).isEqualTo("Chennai");
        assertThat(apartment.getPricePerNight()).isEqualByComparingTo("2500");
        assertThat(apartment.getMaxGuests()).isEqualTo(2);
        assertThat(apartment.getFloorNumber()).isEqualTo(3);
        assertThat(apartment.getHasElevator()).isNull();
    }

    // ------------------------------------------------------------ shared rules

    @Nested
    @DisplayName("rules every type shares")
    class CommonRules {

        @Test
        @DisplayName("a missing type is rejected")
        void missingType() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.setType(null);

            assertRejected(request, "property.type.required", "type");
        }

        @Test
        @DisplayName("a zero price is rejected for every type")
        void zeroPriceForEveryType() {
            for (CreatePropertyRequest request : TestRequests.oneValidPerType()) {
                request.setPricePerNight(BigDecimal.ZERO);

                assertRejected(request, "property.price.positive", "pricePerNight");
            }
        }

        @Test
        @DisplayName("a price with more decimals than the currency uses is rejected")
        void tooManyDecimals() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.setPricePerNight(new BigDecimal("2500.125"));

            assertRejected(request, "property.price.precision", "pricePerNight");
        }

        @Test
        @DisplayName("trailing zeros do not count as extra decimals")
        void trailingZerosAreFine() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.setPricePerNight(new BigDecimal("2500.0000"));

            assertThat(factory.create(request, host).getPricePerNight()).isEqualByComparingTo("2500");
        }

        @Test
        @DisplayName("a missing currency is rejected")
        void missingCurrency() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.setCurrency(null);

            assertRejected(request, "property.currency.required", "currency");
        }

        @Test
        @DisplayName("a listing must sleep at least one guest")
        void zeroGuests() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.setMaxGuests(0);

            assertRejected(request, "property.guests.min", "maxGuests");
        }

        @Test
        @DisplayName("negative bathrooms are rejected")
        void negativeBathrooms() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.setBathrooms(-1);

            assertRejected(request, "property.bathrooms.negative", "bathrooms");
        }

        @Test
        @DisplayName("shared rules run before type-specific ones")
        void templateMethodOrder() {
            CreatePropertyRequest request = TestRequests.validVilla();
            request.setPricePerNight(BigDecimal.ZERO);
            request.getAttributes().remove("plotAreaSqm");

            assertRejected(request, "property.price.positive", "pricePerNight");
        }
    }

    // ------------------------------------------------------- attribute parsing

    @Nested
    @DisplayName("type-specific attributes")
    class AttributeParsing {

        @Test
        @DisplayName("a missing required attribute is reported by its label")
        void missingRequired() {
            CreatePropertyRequest request = TestRequests.validVilla();
            request.getAttributes().remove("plotAreaSqm");

            PropertyValidationException ex = assertRejected(
                    request, "property.attribute.required", "attributes[plotAreaSqm]");
            assertThat(TestMessages.english(ex)).isEqualTo("Plot area (m²) is required for this type of property.");
        }

        @Test
        @DisplayName("a value that is not a number is rejected")
        void unreadableNumber() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.getAttributes().put("floorNumber", "third");

            assertRejected(request, "property.attribute.format", "attributes[floorNumber]");
        }

        @Test
        @DisplayName("a value outside a choice list is rejected")
        void unknownChoice() {
            CreatePropertyRequest request = TestRequests.validCabin();
            request.getAttributes().put("heatingType", "MAGIC");

            assertRejected(request, "property.attribute.choice", "attributes[heatingType]");
        }

        @Test
        @DisplayName("an attribute that belongs to another type is rejected")
        void attributeFromAnotherType() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.getAttributes().put("plotAreaSqm", "300");

            assertRejected(request, "property.attribute.unknown", "attributes[plotAreaSqm]");
        }

        @Test
        @DisplayName("blank values count as not supplied, as HTML forms send them")
        void blanksIgnored() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.getAttributes().put("plotAreaSqm", "");
            request.getAttributes().put("hasElevator", "  ");

            Apartment apartment = (Apartment) factory.create(request, host);

            assertThat(apartment.getHasElevator()).isNull();
        }

        @Test
        @DisplayName("choice codes are normalised the same way under a Turkish default locale")
        void choiceNormalisationIgnoresDefaultLocale() {
            Locale original = Locale.getDefault();
            try {
                // In Turkish, upper-case "i" is "İ" (dotted), so a locale-sensitive
                // toUpperCase() would turn "electric" into "ELECTRİC" and reject it.
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                CreatePropertyRequest request = TestRequests.validCabin();
                request.getAttributes().put("heatingType", "electric");

                Cabin cabin = (Cabin) factory.create(request, host);

                assertThat(cabin.getHeatingType()).isEqualTo("ELECTRIC");
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    // ------------------------------------------------------ per-type rules

    @Nested
    @DisplayName("apartment rules")
    class ApartmentRules {

        @Test
        @DisplayName("floor number cannot be negative")
        void negativeFloor() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.getAttributes().put("floorNumber", "-1");

            assertRejected(request, "property.apartment.floor.negative", "attributes[floorNumber]");
        }

        @Test
        @DisplayName("above floor 4 the host must say whether there is a lift")
        void highFloorNeedsLiftAnswer() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.getAttributes().put("floorNumber", "7");

            PropertyValidationException ex = assertRejected(
                    request, "property.apartment.elevator.required", "attributes[hasElevator]");
            assertThat(TestMessages.english(ex)).contains("floor 4");
        }

        @Test
        @DisplayName("an explicit 'no lift' is an answer, and is stored")
        void highFloorExplicitNoLift() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.getAttributes().put("floorNumber", "7");
            request.getAttributes().put("hasElevator", "false");

            Apartment apartment = (Apartment) factory.create(request, host);

            assertThat(apartment.getHasElevator()).isFalse();
        }

        @Test
        @DisplayName("up to floor 4 the lift may be left unstated")
        void lowFloorMayOmitLift() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.getAttributes().put("floorNumber", "4");

            assertThat(((Apartment) factory.create(request, host)).getHasElevator()).isNull();
        }

        @Test
        @DisplayName("an apartment needs at least one bedroom")
        void noBedrooms() {
            CreatePropertyRequest request = TestRequests.validApartment();
            request.setBedrooms(0);

            assertRejected(request, "property.apartment.bedrooms.min", "bedrooms");
        }
    }

    @Nested
    @DisplayName("villa rules")
    class VillaRules {

        @Test
        @DisplayName("plot area must be at least 100 m²")
        void smallPlot() {
            CreatePropertyRequest request = TestRequests.validVilla();
            request.getAttributes().put("plotAreaSqm", "99.99");

            assertRejected(request, "property.villa.plotArea.min", "attributes[plotAreaSqm]");
        }

        @Test
        @DisplayName("exactly 100 m² is accepted")
        void boundaryPlot() {
            CreatePropertyRequest request = TestRequests.validVilla();
            request.getAttributes().put("plotAreaSqm", "100");

            assertThat(factory.create(request, host).getType()).isEqualTo(PropertyType.VILLA);
        }

        @Test
        @DisplayName("two guests is fine for an apartment but not for a villa")
        void smallCapacity() {
            CreatePropertyRequest villa = TestRequests.validVilla();
            villa.setMaxGuests(2);
            CreatePropertyRequest apartment = TestRequests.validApartment();
            apartment.setMaxGuests(2);

            assertRejected(villa, "property.villa.guests.min", "maxGuests");
            assertThat(factory.create(apartment, host).getMaxGuests()).isEqualTo(2);
        }

        @Test
        @DisplayName("a villa needs at least two bedrooms")
        void oneBedroom() {
            CreatePropertyRequest request = TestRequests.validVilla();
            request.setBedrooms(1);

            assertRejected(request, "property.villa.bedrooms.min", "bedrooms");
        }
    }

    @Nested
    @DisplayName("cabin rules")
    class CabinRules {

        @Test
        @DisplayName("heating type is required")
        void heatingRequired() {
            CreatePropertyRequest request = TestRequests.validCabin();
            request.getAttributes().remove("heatingType");

            assertRejected(request, "property.attribute.required", "attributes[heatingType]");
        }

        @Test
        @DisplayName("distance to town must be within 0–200 km, inclusive")
        void distanceBounds() {
            for (String accepted : List.of("0", "200")) {
                CreatePropertyRequest request = TestRequests.validCabin();
                request.getAttributes().put("distanceToTownKm", accepted);
                assertThat(factory.create(request, host).getType()).isEqualTo(PropertyType.CABIN);
            }
            for (String rejected : List.of("-0.5", "200.01")) {
                CreatePropertyRequest request = TestRequests.validCabin();
                request.getAttributes().put("distanceToTownKm", rejected);
                assertRejected(request, "property.cabin.distance.range", "attributes[distanceToTownKm]");
            }
        }
    }

    @Nested
    @DisplayName("studio rules")
    class StudioRules {

        @Test
        @DisplayName("a studio sleeps at most three")
        void tooManyGuests() {
            CreatePropertyRequest request = TestRequests.validStudio();
            request.setMaxGuests(4);

            assertRejected(request, "property.studio.guests.max", "maxGuests");
        }

        @Test
        @DisplayName("a studio must be at least 12 m²")
        void tooSmall() {
            CreatePropertyRequest request = TestRequests.validStudio();
            request.getAttributes().put("areaSqm", "11.99");

            assertRejected(request, "property.studio.area.min", "attributes[areaSqm]");
        }

        @Test
        @DisplayName("bedrooms are forced to zero without changing the caller's request")
        void bedroomsForcedToZero() {
            CreatePropertyRequest request = TestRequests.validStudio();
            request.setBedrooms(2);

            Property studio = factory.create(request, host);

            assertThat(studio.getBedrooms()).isZero();
            assertThat(request.getBedrooms()).isEqualTo(2);
        }
    }

    // ----------------------------------------------------------------- helper

    /**
     * Asserts the factory rejects the request with this key and field, and that the
     * key exists in messages.properties with every argument filled in.
     */
    private PropertyValidationException assertRejected(CreatePropertyRequest request,
                                                       String expectedKey,
                                                       String expectedField) {
        Throwable thrown = catchThrowable(() -> factory.create(request, host));

        assertThat(thrown).isInstanceOf(PropertyValidationException.class);
        PropertyValidationException ex = (PropertyValidationException) thrown;
        assertThat(ex.getMessageKey()).isEqualTo(expectedKey);
        assertThat(ex.getField()).isEqualTo(expectedField);
        assertThat(TestMessages.english(ex)).isNotBlank().doesNotContain("{");
        return ex;
    }
}
