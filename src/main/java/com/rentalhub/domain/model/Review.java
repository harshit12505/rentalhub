package com.rentalhub.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;

/**
 * A guest's rating and comment for a listing they stayed at. One per guest per
 * listing (a unique constraint since V2). Audited by Envers, so reviews_aud keeps
 * every edit, and a deleted review's last state.
 */
@Entity
@Table(name = "reviews")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private User author;

    /** 1 to 5, enforced by a CHECK constraint in the database. */
    @Column(nullable = false)
    private Integer rating;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    public Review(Property property, User author, int rating, String comment) {
        this.property = property;
        this.author = author;
        this.rating = rating;
        this.comment = comment;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
