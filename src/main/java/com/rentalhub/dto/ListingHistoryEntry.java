package com.rentalhub.dto;

import java.time.Instant;
import java.util.List;

/**
 * One change in a listing's history: which revision, when, by whom, and what changed.
 *
 * @param changedBy     who, as recorded: {@code user:<id>}, {@code system:<job>} or {@code anonymous}
 * @param changedByName the user's name when {@code changedBy} is a user, else null
 * @param changes       field by field, the value before and after. For a creation, every
 *                      field that was set (from null); for a deletion, empty
 */
public record ListingHistoryEntry(
        long revision,
        Instant changedAt,
        String changedBy,
        String changedByName,
        ChangeType type,
        List<FieldChange> changes) {

    public ListingHistoryEntry {
        changes = List.copyOf(changes);
    }

    public enum ChangeType { CREATED, UPDATED, DELETED }

    /**
     * @param field type-specific fields are named as in requests and errors, e.g. {@code attributes[hasPool]}
     */
    public record FieldChange(String field, Object from, Object to) {
    }
}
