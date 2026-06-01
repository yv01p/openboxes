package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

// GET-only cache-with-refresh entity (T7). Grails domain has `cache true`; mirrors the T6
// ProductCatalog read-entity pattern (protected ctor, getters only — no setters). Zero React
// callers (T1 audit §7: "Verbs (React): none") → FD#1 default GET-only.
//
// Audit-shape: timestamp-only listener per the T1 audit §6 prescription and the T2–T6 canonical
// entity template. NO @CreatedBy/@LastModifiedBy — the Grails domain has only Date
// dateCreated/lastUpdated (no User createdBy/updatedBy), and product_catalog_item has no
// created_by_id/updated_by_id columns. The AuditingEntityListener is INERT on the GET-only path
// today (no writes ever flow through this entity here) but is mapped per the canonical audit-shape
// so this stays forward-compatible if a write path is ever added. Uses Instant, mirroring
// ProductCatalog.java's audit block.
//
// Catalog-internal FKs (product, productCatalog) are @ManyToOne per FD#2. productCatalog is the
// owning side of the T6 forward-decl (@OneToMany(mappedBy="productCatalog") on ProductCatalog).
@Entity
@Table(name = "product_catalog_item")
@EntityListeners(AuditingEntityListener.class)
public class ProductCatalogItem {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column). Matches the canonical
    // template; the live column is NOT NULL.
    @Version
    private Long version;

    // bit(1) NOT NULL (stricter than product_catalog's nullable active). Grails domain default TRUE.
    @Column(nullable = false)
    private Boolean active = true;

    // Catalog-internal FK — @ManyToOne per FD#2. Live column product_id char(38) NOT NULL.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, columnDefinition = "CHAR(38)")
    private Product product;

    // Catalog-internal FK — @ManyToOne per FD#2. Live column product_catalog_id char(38) NOT NULL.
    // Owning side of the T6 forward-decl @OneToMany(mappedBy="productCatalog") on ProductCatalog.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_catalog_id", nullable = false, columnDefinition = "CHAR(38)")
    private ProductCatalog productCatalog;

    // Timestamp-only audit fields (NO User FK columns exist on product_catalog_item).
    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // Read entity: protected no-arg ctor + getters only (mirrors ProductCatalog.java).
    protected ProductCatalogItem() {}

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public Boolean getActive() { return active; }
    public Product getProduct() { return product; }
    public ProductCatalog getProductCatalog() { return productCatalog; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
