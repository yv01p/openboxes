package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

// GET-only read entity (T11) — the LAST GET-only entity of the catalog migration. A unit-of-measure
// conversion rate (from one UoM to another). Zero React callers (T1 audit §7: "Verbs (React): none")
// → FD#1 default GET-only. Mirrors the T7 ProductCatalogItem read-entity pattern (protected ctor,
// getters only — no setters; Instant audit; the `active` Boolean field-default).
//
// CACHE-backed (heuristic cache per the T1 audit §5/§8: low churn, paired with the existing
// UnitOfMeasureCache) — see cache/UnitOfMeasureConversionCache.java. Unlike the T8/T9/T10 repo-backed
// GET-only entities, get/list are served from the app-level cache (like T6/T7).
//
// Audit-shape: timestamp-only listener per the T1 audit §6 prescription and the canonical
// entity template. NO @CreatedBy/@LastModifiedBy — the Grails domain has only Date
// dateCreated/lastUpdated (no User createdBy/updatedBy), and unit_of_measure_conversion has no
// created_by_id/updated_by_id columns. The AuditingEntityListener is INERT on the GET-only path
// today (no writes ever flow through this entity here) but is mapped per the canonical audit-shape
// so this stays forward-compatible if a write path is ever added.
//
// TYPE NOTE: the Grails domain declares dateCreated/lastUpdated as `Date`; mapped here as `Instant`
// for consistency with T6/T7/T9/T10 (both map to datetime; uniform JSON).
//
// Catalog-internal FKs (fromUnitOfMeasure, toUnitOfMeasure) are @ManyToOne per FD#2, both
// optional=false (both columns are NOT NULL in the live schema). Writes stay Grails-side (GSP
// UnitOfMeasureConversionController; no validator to port — only nullable constraints in the domain).
@Entity
@Table(name = "unit_of_measure_conversion")
@EntityListeners(AuditingEntityListener.class)
public class UnitOfMeasureConversion {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column). Live column is NOT NULL.
    @Version
    private Long version;

    // bit(1) NOT NULL. Grails domain field-default TRUE (mirrors ProductCatalogItem's active).
    @Column(nullable = false)
    private Boolean active = true;

    // Catalog-internal FK — @ManyToOne per FD#2. Live column from_unit_of_measure_id char(38) NOT NULL.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_unit_of_measure_id", nullable = false, columnDefinition = "CHAR(38)")
    private UnitOfMeasure fromUnitOfMeasure;

    // Catalog-internal FK — @ManyToOne per FD#2. Live column to_unit_of_measure_id char(38) NOT NULL.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_unit_of_measure_id", nullable = false, columnDefinition = "CHAR(38)")
    private UnitOfMeasure toUnitOfMeasure;

    // decimal(19,8) NOT NULL.
    @Column(name = "conversion_rate", nullable = false)
    private BigDecimal conversionRate;

    // Timestamp-only audit fields (NO User FK columns exist on unit_of_measure_conversion).
    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // Read entity: protected no-arg ctor + getters only (mirrors ProductCatalogItem.java).
    protected UnitOfMeasureConversion() {}

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public Boolean getActive() { return active; }
    public UnitOfMeasure getFromUnitOfMeasure() { return fromUnitOfMeasure; }
    public UnitOfMeasure getToUnitOfMeasure() { return toUnitOfMeasure; }
    public BigDecimal getConversionRate() { return conversionRate; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
