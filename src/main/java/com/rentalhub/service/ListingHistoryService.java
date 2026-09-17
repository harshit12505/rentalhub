package com.rentalhub.service;

import com.rentalhub.audit.AuditActor;
import com.rentalhub.audit.Revision;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.ListingHistoryEntry;
import com.rentalhub.dto.ListingHistoryEntry.ChangeType;
import com.rentalhub.dto.ListingHistoryEntry.FieldChange;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A listing's history, read from the audit tables with Envers' AuditReader.
 *
 * Envers stores whole snapshots: the listing as it was after each change. What a
 * reader wants is what changed. So the snapshots are compared one after another, field
 * by field, and only the differences are reported.
 */
@Service
public class ListingHistoryService {

    @PersistenceContext
    private EntityManager entityManager;

    private final PropertyRepository properties;
    private final UserRepository users;

    public ListingHistoryService(PropertyRepository properties, UserRepository users) {
        this.properties = properties;
        this.users = users;
    }

    /**
     * Every recorded change to the listing, oldest first. Only its host may see it; that
     * still works after the listing was deleted, because the deletion's history row keeps
     * the listing's last state (store_data_at_delete), host included.
     */
    @Transactional(readOnly = true)
    public List<ListingHistoryEntry> history(long propertyId, long actingUserId) {
        @SuppressWarnings("unchecked")
        List<Object[]> revisions = AuditReaderFactory.get(entityManager).createQuery()
                // false: return [entity, revision, type] rows; true: include deletions
                .forRevisionsOfEntity(Property.class, false, true)
                .add(AuditEntity.id().eq(propertyId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();
        if (revisions.isEmpty()) {
            // No history, yet the listing may exist: created before auditing began (V2), or
            // only ever changed behind the application's back. Then its history is empty.
            Property listing = properties.findById(propertyId)
                    .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
            requireHost(listing, actingUserId);
            return List.of();
        }
        requireHost((Property) revisions.getLast()[0], actingUserId);

        Map<Long, String> names = userNames(revisions);
        List<ListingHistoryEntry> entries = new ArrayList<>(revisions.size());
        Map<String, Object> before = Map.of();
        for (Object[] row : revisions) {
            Property state = (Property) row[0];
            Revision revision = (Revision) row[1];
            RevisionType type = (RevisionType) row[2];

            Map<String, Object> after = snapshot(state);
            List<FieldChange> changes = type == RevisionType.DEL ? List.of() : differences(before, after);
            String changedBy = revision.getChangedBy();
            entries.add(new ListingHistoryEntry(revision.getId(), revision.changedAt(), changedBy,
                    AuditActor.userIdOf(changedBy).map(names::get).orElse(null), changeType(type), changes));
            before = after;
        }
        return entries;
    }

    /** The host as of this state; for a deleted listing, its last host. */
    private static void requireHost(Property listing, long actingUserId) {
        if (listing.getHost().getId() != actingUserId) {
            throw new OperationNotAllowedException("property.history.notHost");
        }
    }

    /**
     * The fields the history compares, by name. The type-specific ones come from
     * typeAttributes(), so no property type needs code of its own here.
     */
    static Map<String, Object> snapshot(Property listing) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", listing.getType());
        fields.put("title", listing.getTitle());
        fields.put("description", listing.getDescription());
        fields.put("city", listing.getCity());
        fields.put("country", listing.getCountry());
        fields.put("address", listing.getAddress());
        fields.put("pricePerNight", listing.getPricePerNight());
        fields.put("currency", listing.getCurrency());
        fields.put("maxGuests", listing.getMaxGuests());
        fields.put("bedrooms", listing.getBedrooms());
        fields.put("bathrooms", listing.getBathrooms());
        fields.put("active", listing.isActive());
        fields.put("availableUntil", listing.getAvailableUntil());
        fields.put("hostId", listing.getHost() == null ? null : listing.getHost().getId());
        listing.typeAttributes().forEach((name, value) -> fields.put("attributes[" + name + "]", value));
        return fields;
    }

    /** Fields whose value differs between two snapshots, in the order the fields are listed. */
    static List<FieldChange> differences(Map<String, Object> before, Map<String, Object> after) {
        List<FieldChange> changes = new ArrayList<>();
        after.forEach((field, value) -> {
            Object previous = before.get(field);
            if (!sameValue(previous, value)) {
                changes.add(new FieldChange(field, previous, value));
            }
        });
        return changes;
    }

    /** BigDecimals by value, not by scale: 2500.00 and 2500.0000 are the same price. */
    private static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static ChangeType changeType(RevisionType type) {
        return switch (type) {
            case ADD -> ChangeType.CREATED;
            case MOD -> ChangeType.UPDATED;
            case DEL -> ChangeType.DELETED;
        };
    }

    /** Names of the users who made these revisions, fetched in one query. */
    private Map<Long, String> userNames(List<Object[]> revisions) {
        Set<Long> ids = revisions.stream()
                .map(row -> ((Revision) row[1]).getChangedBy())
                .map(AuditActor::userIdOf)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toSet());
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
    }
}
