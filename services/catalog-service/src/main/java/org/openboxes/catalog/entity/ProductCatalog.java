package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.List;

// GET-only cache-with-refresh entity (T6). Grails domain has `cache true`; mirrors the
// ProductType/Attribute read-entity pattern (protected ctor, getters only — no setters).
//
// Audit-shape: timestamp-only listener per the T1 audit §6 prescription for the 5 timestamp-only
// entities and the T2–T5 canonical entity template. NO @CreatedBy/@LastModifiedBy — the Grails
// domain has only Date dateCreated/lastUpdated (no User createdBy/updatedBy), and product_catalog
// has no created_by_id/updated_by_id columns. The AuditingEntityListener is INERT on the GET-only
// path today (no writes ever flow through this entity here) but is mapped per the canonical
// audit-shape so this stays forward-compatible if a write path is ever added. Uses Instant,
// mirroring ProductSupplier.java's audit block.
//
@Entity
@Table(name = "product_catalog")
@EntityListeners(AuditingEntityListener.class)
public class ProductCatalog {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "longtext")
    private String description;

    // bit(1) DEFAULT NULL — maps to Boolean (nullable). Grails domain default is TRUE.
    private Boolean active = true;

    private String color;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column). Matches the canonical
    // template; the live column is NOT NULL.
    @Version
    private Long version;

    // Timestamp-only audit fields (NO User FK columns exist on product_catalog).
    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // T7 forward-decl appendback: the inverse side of ProductCatalogItem.productCatalog. Mapped for
    // ORM/Grails-domain fidelity (Grails hasMany productCatalogItems). LAZY. NOT surfaced in
    // ProductCatalogDto: ProductCatalog is cache-served, and initializing a lazy collection off a
    // cached/detached entity would risk LazyInitializationException; no consumer needs it (GET-only,
    // zero React callers). The cache path stays scalar/proxy-id-only-safe.
    @OneToMany(mappedBy = "productCatalog", fetch = FetchType.LAZY)
    private List<ProductCatalogItem> productCatalogItems;

    // Read entity: protected no-arg ctor + getters only (mirrors ProductType.java).
    protected ProductCatalog() {}

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Boolean getActive() { return active; }
    public String getColor() { return color; }
    public Long getVersion() { return version; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public List<ProductCatalogItem> getProductCatalogItems() { return productCatalogItems; }
}
