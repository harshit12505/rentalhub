package com.rentalhub.factory;

import com.rentalhub.exception.PropertyValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TypeAttributesTest {

    private static final List<AttributeSpec> SPECS = List.of(
            AttributeSpec.required("rooms", AttributeKind.INTEGER),
            AttributeSpec.optional("seaView", AttributeKind.BOOLEAN));

    @Test
    @DisplayName("surrounding whitespace is ignored and missing optionals read as null")
    void parsesTrimmedValues() {
        TypeAttributes attributes = TypeAttributes.parse(Map.of("rooms", " 3 "), SPECS);

        assertThat(attributes.integer("rooms")).isEqualTo(3);
        assertThat(attributes.bool("seaView")).isNull();
    }

    @Test
    @DisplayName("booleans accept only true or false, in any case")
    void strictBooleans() {
        assertThat(TypeAttributes.parse(Map.of("rooms", "1", "seaView", "TRUE"), SPECS).bool("seaView")).isTrue();

        assertThatExceptionOfType(PropertyValidationException.class)
                .isThrownBy(() -> TypeAttributes.parse(Map.of("rooms", "1", "seaView", "yes"), SPECS))
                .extracting(PropertyValidationException::getMessageKey)
                .isEqualTo("property.attribute.format");
    }

    @Test
    @DisplayName("a null attribute map is treated as empty")
    void nullMap() {
        assertThatExceptionOfType(PropertyValidationException.class)
                .isThrownBy(() -> TypeAttributes.parse(null, SPECS))
                .extracting(PropertyValidationException::getMessageKey)
                .isEqualTo("property.attribute.required");
    }

    @Test
    @DisplayName("reading an undeclared or wrongly typed attribute is a programming error")
    void creatorBugsFailLoudly() {
        TypeAttributes attributes = TypeAttributes.parse(Map.of("rooms", "2"), SPECS);

        assertThatIllegalArgumentException().isThrownBy(() -> attributes.decimal("rooms"));
        assertThatIllegalArgumentException().isThrownBy(() -> attributes.integer("floors"));
    }

    @Test
    @DisplayName("specs reject inconsistent choice lists")
    void specConsistency() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AttributeSpec("heating", AttributeKind.CHOICE, true, List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AttributeSpec("rooms", AttributeKind.INTEGER, true, List.of("ONE")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AttributeSpec.requiredChoice("heating", "gas"));
    }
}
