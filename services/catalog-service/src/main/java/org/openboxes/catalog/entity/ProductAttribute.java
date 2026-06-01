package org.openboxes.catalog.entity;

import jakarta.persistence.*;

// GET-only read entity (T8). It is the value of a particular Attribute for a particular Product, with
// optional supplier/uom context. Zero React callers (T1 audit §7: ProductAttribute verbs = "none") →
// FD#1 default GET-only. Read-entity shape: protected no-arg ctor + getters only (no setters),
// mirroring the T6/T7 ProductCatalog(Item) read entities.
//
// T8 DEVIATES from the T6/T7 template in three ways (do NOT carry the template's audit/cache here):
//   1. NO audit fields / NO @EntityListeners. The live product_attribute table has NO
//      date_created/last_updated columns and the Grails domain has no dateCreated/lastUpdated either.
//      T8 is the only entity in the catalog set with no audit shape at all.
//   2. NO cache (repo-backed service, like ProductService) — the Grails domain has no `cache true`.
//   3. attributes_idx INT (a Grails indexed-collection ordering artifact) is left UNMAPPED;
//      ddl-auto=validate tolerates unmapped columns and T8 does not own that ordering concern.
//
// LIVE-SCHEMA DEVIATION FROM GRAILS (live schema is authoritative): ALL FOUR FK columns are
// DB-nullable (`DEFAULT NULL`) in the live product_attribute table — including product_id (despite
// the Grails `belongsTo=[product:Product]`) and attribute_id (despite the Grails
// `attribute(nullable:false)`, which is an app-layer-only constraint). All four @ManyToOne are
// therefore mapped NULLABLE: NO optional=false, NO nullable=false on the @JoinColumn. This matches
// the SHOW CREATE TABLE ground truth and keeps ddl-auto=validate green.
//
// The `value VARCHAR(255)` scalar column is the entire point of the entity (the attribute's value)
// and IS mapped/exposed; it is DB-nullable. All four FKs (attribute, product, unitOfMeasure,
// productSupplier) are catalog-internal → @ManyToOne per FD#2, exposed as raw id strings by the DTO.
@Entity
@Table(name = "product_attribute")
public class ProductAttribute {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column). Matches the canonical
    // template; the live column is NOT NULL.
    @Version
    private Long version;

    // The scalar payload — value varchar(255), DB-nullable. This is the value of the attribute.
    @Column
    private String value;

    // All 4 FKs are catalog-internal → @ManyToOne per FD#2, exposed as raw id strings by the DTO.
    // ALL nullable per the live schema (every FK column is `DEFAULT NULL`): no optional=false, no
    // nullable=false on the @JoinColumn — a deliberate Grails-vs-live deviation (see header).

    // attribute_id char(38) DEFAULT NULL. Grails attribute(nullable:false) is app-layer-only.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attribute_id", columnDefinition = "CHAR(38)")
    private Attribute attribute;

    // product_id char(38) DEFAULT NULL. Grails belongsTo=[product:Product] does NOT make the column
    // NOT NULL in the live schema.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", columnDefinition = "CHAR(38)")
    private Product product;

    // unit_of_measure_id char(38) DEFAULT NULL (Grails unitOfMeasure(nullable:true)).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_of_measure_id", columnDefinition = "CHAR(38)")
    private UnitOfMeasure unitOfMeasure;

    // product_supplier_id char(38) DEFAULT NULL (Grails productSupplier(nullable:true)).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_supplier_id", columnDefinition = "CHAR(38)")
    private ProductSupplier productSupplier;

    // attributes_idx INT (Grails list-index artifact) is intentionally UNMAPPED.

    // Read entity: protected no-arg ctor + getters only (mirrors ProductCatalogItem.java).
    protected ProductAttribute() {}

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getValue() { return value; }
    public Attribute getAttribute() { return attribute; }
    public Product getProduct() { return product; }
    public UnitOfMeasure getUnitOfMeasure() { return unitOfMeasure; }
    public ProductSupplier getProductSupplier() { return productSupplier; }
}
