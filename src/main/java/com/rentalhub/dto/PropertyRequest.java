package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One request object for every property type, used both to create a listing (POST)
 * and to replace one (PUT). PUT is a full replacement: the body is the listing's
 * complete new state, checked by the same per-type rules as creation.
 *
 * Fields every listing has are typed properties. Fields only one type has (a villa's
 * plot area, a cabin's heating) travel in {@link #attributes} as name → text, and the
 * chosen type's creator declares, parses and checks them. That is why adding a
 * property type never changes this class.
 *
 * Validation messages are message keys in braces, resolved from messages.properties
 * in the user's language.
 *
 * A mutable class rather than a record because Thymeleaf form binding needs setters.
 */
@Getter
@Setter
public class PropertyRequest {

    @NotNull(message = "{property.type.required}")
    private PropertyType type;

    @NotBlank(message = "{validation.required}")
    @Size(max = 150, message = "{validation.maxLength}")
    private String title;

    @NotBlank(message = "{validation.required}")
    private String description;

    @NotBlank(message = "{validation.required}")
    @Size(max = 100, message = "{validation.maxLength}")
    private String city;

    @NotBlank(message = "{validation.required}")
    @Size(max = 100, message = "{validation.maxLength}")
    private String country;

    @Size(max = 255, message = "{validation.maxLength}")
    private String address;

    /** 15 integer digits + 4 decimals is exactly what the NUMERIC(19,4) column can hold. */
    @NotNull(message = "{validation.required}")
    @Positive(message = "{property.price.positive}")
    @Digits(integer = 15, fraction = 4, message = "{property.price.digits}")
    private BigDecimal pricePerNight;

    @NotNull(message = "{property.currency.required}")
    private Currency currency;

    @NotNull(message = "{validation.required}")
    @Min(value = 1, message = "{property.guests.min}")
    private Integer maxGuests;

    @Min(value = 0, message = "{validation.min}")
    private Integer bedrooms = 0;

    @Min(value = 0, message = "{validation.min}")
    private Integer bathrooms = 0;

    @FutureOrPresent(message = "{property.availableUntil.past}")
    private LocalDate availableUntil;

    /** Type-specific fields by name; see the chosen type's creator for what it expects. */
    private Map<String, String> attributes = new LinkedHashMap<>();
}
