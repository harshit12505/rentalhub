package com.rentalhub.factory;

import com.rentalhub.exception.PropertyValidationException;
import org.springframework.context.support.DefaultMessageSourceResolvable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The type-specific attributes of one request, parsed and checked against the
 * creator's {@link AttributeSpec}s.
 *
 * Parsing is the generic half of validation, identical for every property type:
 * is each required attribute present, can each value be read as its declared kind,
 * is each CHOICE one of the listed codes, and has nothing been sent that this type
 * does not have. The rules that make each type different (a villa's plot must be at
 * least 100 m²) stay in the creators' {@code validateSpecific}.
 */
public final class TypeAttributes {

    private final Map<String, AttributeSpec> specs;
    private final Map<String, Object> values;

    private TypeAttributes(Map<String, AttributeSpec> specs, Map<String, Object> values) {
        this.specs = specs;
        this.values = values;
    }

    /**
     * @param raw   the request's attribute map; blank values count as "not supplied",
     *              because an HTML form sends an empty string for an untouched field
     * @param specs what the property type declares
     * @throws PropertyValidationException naming the offending attribute
     */
    public static TypeAttributes parse(Map<String, String> raw, List<AttributeSpec> specs) {
        Map<String, AttributeSpec> byName = new LinkedHashMap<>();
        specs.forEach(spec -> byName.put(spec.name(), spec));
        Map<String, String> input = raw == null ? Map.of() : raw;

        // A villa's plot area sent with an apartment is almost certainly a client
        // bug; rejecting it beats silently dropping data the user typed.
        input.forEach((name, value) -> {
            if (!byName.containsKey(name) && !isBlank(value)) {
                throw PropertyValidationException.onField(
                        AttributeSpec.fieldPathOf(name), "property.attribute.unknown", name);
            }
        });

        Map<String, Object> values = new HashMap<>();
        for (AttributeSpec spec : byName.values()) {
            String text = input.get(spec.name());
            if (isBlank(text)) {
                if (spec.required()) {
                    throw PropertyValidationException.onField(
                            spec.fieldPath(), "property.attribute.required", label(spec));
                }
                continue;
            }
            Object value;
            try {
                value = spec.kind().parse(text.strip());
            } catch (IllegalArgumentException unreadable) {
                throw PropertyValidationException.onField(
                        spec.fieldPath(), "property.attribute.format", label(spec));
            }
            if (!spec.choices().isEmpty() && !spec.choices().contains(value)) {
                throw PropertyValidationException.onField(
                        spec.fieldPath(), "property.attribute.choice", label(spec));
            }
            values.put(spec.name(), value);
        }
        return new TypeAttributes(byName, values);
    }

    /** @return the value, or null if the attribute was not supplied */
    public Integer integer(String name) {
        return get(name, Integer.class);
    }

    public BigDecimal decimal(String name) {
        return get(name, BigDecimal.class);
    }

    public Boolean bool(String name) {
        return get(name, Boolean.class);
    }

    public String choice(String name) {
        return get(name, String.class);
    }

    /**
     * Asking for an attribute the creator never declared, or as the wrong type, is a
     * programming mistake in the creator, not bad user input, so it fails loudly.
     */
    private <V> V get(String name, Class<V> type) {
        AttributeSpec spec = specs.get(name);
        if (spec == null || spec.kind().javaType() != type) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not a declared " + type.getSimpleName() + " attribute");
        }
        return type.cast(values.get(name));
    }

    /** The label is resolved later, in the user's language, when the message is rendered. */
    private static DefaultMessageSourceResolvable label(AttributeSpec spec) {
        return new DefaultMessageSourceResolvable(spec.labelKey());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
