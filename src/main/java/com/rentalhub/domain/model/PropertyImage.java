package com.rentalhub.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "property_images")
@Getter
@Setter
@NoArgsConstructor
public class PropertyImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(nullable = false, length = 500)
    private String url;

    /** Object key in the S3 bucket, kept so we can delete the file later. */
    @Column(name = "s3_key", length = 500)
    private String s3Key;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    public PropertyImage(String url, String s3Key, Integer sortOrder) {
        this.url = url;
        this.s3Key = s3Key;
        this.sortOrder = sortOrder == null ? 0 : sortOrder;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
