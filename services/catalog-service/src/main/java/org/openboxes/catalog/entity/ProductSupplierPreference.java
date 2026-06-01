package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

// T3 ProductSupplierPreference — write-path entity per phase 5.5 write-contract reconciliation.
// Flat cross-service FKs (destinationPartyId, preferenceTypeId → Organization/PreferenceType in
// non-catalog services) per FD#2 (raw CHAR(38), NO @ManyToOne). Catalog-internal productSupplier
// FK is @ManyToOne. App-layer pair-uniqueness (A9 — NO DB unique index) enforced by service.
// Audit via FD#8 Option-A (AuditingEntityListener + JwtAuditorAware, reusing T2 infra).
//
// Column set mapped against LIVE openboxes-db product_supplier_preference table; ddl-auto=validate
// proof DEFERRED to T14 done-gate (static cross-check substitutes here — see task instructions).
@Entity
@Table(name = "product_supplier_preference")
@EntityListeners(AuditingEntityListener.class)
public class ProductSupplierPreference {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // Catalog-internal FK — @ManyToOne per FD#2.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_supplier_id")
    private ProductSupplier productSupplier;

    // Cross-service FK (Organization in org-service) — raw String id per FD#2 (NO @ManyToOne).
    // Nullable per GORM constraints and live schema.
    @Column(name = "destination_party_id", columnDefinition = "CHAR(38)")
    private String destinationPartyId;

    // Cross-service FK (PreferenceType is not a migrated entity) — raw String id per FD#2.
    // Nullable at DB level, though GORM says preferenceType(nullable:false). Following live schema.
    @Column(name = "preference_type_id", columnDefinition = "CHAR(38)")
    private String preferenceTypeId;

    // Nullable varchar(255) per live schema; defaults to varchar(255) under create-mode.
    private String comments;

    // DATETIME columns mapped to Instant, mirroring T2's date_created/last_updated convention.
    @Column(name = "validity_start_date")
    private Instant validityStartDate;

    @Column(name = "validity_end_date")
    private Instant validityEndDate;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column).
    @Version
    private Long version;

    // FD#8 Option-A audit fields. Mirroring T2 ProductSupplier lines 96-109 verbatim.
    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
    @CreatedBy
    @Column(name = "created_by_id", columnDefinition = "CHAR(38)", updatable = false)
    private String createdById;
    @LastModifiedBy
    @Column(name = "updated_by_id", columnDefinition = "CHAR(38)")
    private String updatedById;

    // public (not protected): write-path mappers in dto package construct instances directly.
    public ProductSupplierPreference() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ProductSupplier getProductSupplier() { return productSupplier; }
    public void setProductSupplier(ProductSupplier productSupplier) { this.productSupplier = productSupplier; }

    public String getDestinationPartyId() { return destinationPartyId; }
    public void setDestinationPartyId(String destinationPartyId) { this.destinationPartyId = destinationPartyId; }

    public String getPreferenceTypeId() { return preferenceTypeId; }
    public void setPreferenceTypeId(String preferenceTypeId) { this.preferenceTypeId = preferenceTypeId; }

    public String getComments() { return comments; }
    public void setComments(String comments) { this.comments = comments; }

    public Instant getValidityStartDate() { return validityStartDate; }
    public void setValidityStartDate(Instant validityStartDate) { this.validityStartDate = validityStartDate; }

    public Instant getValidityEndDate() { return validityEndDate; }
    public void setValidityEndDate(Instant validityEndDate) { this.validityEndDate = validityEndDate; }

    public Long getVersion() { return version; }

    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getCreatedById() { return createdById; }
    public String getUpdatedById() { return updatedById; }
}
