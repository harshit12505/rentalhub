package com.rentalhub.factory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Describes one field that only some property types have, such as a villa's plot
 * area.
 *
 * Each creator publishes a list of these. The factory uses them to parse and check
 * the request; forms, detail pages and the GraphQL API use them to render the field
 * without knowing which property type they are looking at. That is what lets a new
 * property type appear everywhere without any view or API code changing.
 *
 * @param name     key in {@code PropertyRequest.attributes} and in {@code Property.typeAttributes()}
 * @param kind     how the raw text is parsed
 * @param required whether a request must supply a value
 * @param choices  allowed upper-case codes for {@link AttributeKind#CHOICE}; empty for every other kind
 */
public record AttributeSpec(String name, AttributeKind kind, boolean required, List<String> choices) {

    public AttributeSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        choices = List.copyOf(choices);
        if ((kind == AttributeKind.CHOICE) == choices.isEmpty()) {
            throw new IllegalArgumentException(
                    "Choices must be listed for CHOICE attributes, and only for them: " + name);
        }
        for (String choice : choices) {
            if (!choice.equals(choice.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Choice codes must be upper case: " + choice);
            }
        }
    }

    public static AttributeSpec required(String name, AttributeKind kind) {
        return new AttributeSpec(name, kind, true, List.of());
    }

    public static AttributeSpec optional(String name, AttributeKind kind) {
        return new AttributeSpec(name, kind, false, List.of());
    }

    public static AttributeSpec requiredChoice(String name, String... choices) {
        return new AttributeSpec(name, AttributeKind.CHOICE, true, List.of(choices));
    }

    /** Message key of this attribute's label, e.g. {@code property.attribute.plotAreaSqm}. */
    public String labelKey() {
        return "property.attribute." + name;
    }

    /** Message key of one choice's label, e.g. {@code property.attribute.heatingType.GAS}. */
    public String choiceLabelKey(String choice) {
        return labelKey() + "." + choice;
    }

    /** Form binding path of the attribute, so errors can be shown next to the right input. */
    public String fieldPath() {
        return fieldPathOf(name);
    }

    public static String fieldPathOf(String name) {
        return "attributes[" + name + "]";
    }
}
