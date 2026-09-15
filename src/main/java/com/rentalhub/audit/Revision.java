package com.rentalhub.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.time.Instant;

/**
 * One audited transaction. Envers writes one of these per transaction that changed an
 * audited entity, and every history row that transaction produced points back to it.
 *
 * Envers' own default revision records only a number and a time. This one also records
 * who made the change, which is the first question anyone reading a history asks.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(ActorRevisionListener.class)
@Getter
public class Revision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    @Column(name = "rev")
    private Long id;

    /** Milliseconds since 1970-01-01 UTC; see {@link #changedAt()}. */
    @RevisionTimestamp
    @Column(name = "revtstmp", nullable = false)
    private long timestamp;

    /** {@code user:<id>}, {@code system:<job>} or {@code anonymous}; see {@link AuditActor}. */
    @Column(name = "changed_by", nullable = false, length = 100)
    @Setter(AccessLevel.PACKAGE)
    private String changedBy;

    public Instant changedAt() {
        return Instant.ofEpochMilli(timestamp);
    }
}
