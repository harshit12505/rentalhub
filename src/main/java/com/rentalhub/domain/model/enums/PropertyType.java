package com.rentalhub.domain.model.enums;

/**
 * The kinds of listing RentalHub supports.
 *
 * Adding a type takes exactly four things: a value here, an entity class extending
 * Property, a creator extending AbstractPropertyCreator, and a Flyway migration for
 * its columns (plus translated labels in the messages files). The factory refuses
 * to start if a value here has no creator.
 */
public enum PropertyType {
    APARTMENT,
    VILLA,
    CABIN,
    STUDIO
}
