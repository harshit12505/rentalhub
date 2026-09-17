package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base class for every listing.
 *
 * SINGLE_TABLE inheritance: all four subtypes share the 'properties' table and
 * are told apart by the 'property_type' discriminator column. This keeps search
 * queries fast (one table, no joins) at the cost of some nullable columns.
 *
 * The @Version field is what makes optimistic locking work later: Hibernate
 * includes "WHERE version = ?" in every update and increments it. If another
 * transaction got there first the update matches zero rows and Hibernate throws,
 * which is how we detect a concurrent booking without ever locking a row.
 *
 * id, version and the timestamps have no setters: they belong to the database and
 * Hibernate, and code that changed the version by hand would defeat the locking.
 *
 * Audited by Envers: every committed change also writes the listing's new state to
 * properties_aud. Every subtype carries {@code @Audited} too, and adding a type means
 * adding its columns to properties_aud as well. Not audited: the images (they get their
 * own storage in phase 7), {@code updatedAt} (the revision records when), and, as
 * Envers does by default, the version.
 */
@Entity
@Table(name = "properties")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "property_type", discriminatorType = DiscriminatorType.STRING, length = 31)
@Audited
@Getter
@Setter
public abstract class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Version
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long version;

    /** The history stores the host's id; users themselves have no history. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private User host;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String country;

    @Column(length = 255)
    private String address;

    /** BigDecimal, never double: binary floating point cannot hold most decimal fractions exactly. */
    @Column(name = "price_per_night", nullable = false, precision = 19, scale = 4)
    private BigDecimal pricePerNight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "max_guests", nullable = false)
    private Integer maxGuests;

    @Column(nullable = false)
    private Integer bedrooms = 0;

    @Column(nullable = false)
    private Integer bathrooms = 0;

    @Column(nullable = false)
    private boolean active = true;

    /** After this date the scheduled job deactivates the listing. */
    @Column(name = "available_until")
    private LocalDate availableUntil;

    @OneToMany(mappedBy = "property", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @NotAudited
    private List<PropertyImage> images = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter(AccessLevel.NONE)
    @NotAudited
    private Instant updatedAt;

    /** Each subclass answers for itself, so Java and the discriminator column cannot disagree. */
    public abstract PropertyType getType();

    /**
     * This subtype's own fields as name → value, in display order, where null means
     * "not stated". The names match the AttributeSpecs its creator declares, which
     * is what lets pages and APIs show any property type without knowing which it is.
     */
    public abstract Map<String, Object> typeAttributes();

    public void addImage(PropertyImage image) {
        image.setProperty(this);
        this.images.add(image);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
