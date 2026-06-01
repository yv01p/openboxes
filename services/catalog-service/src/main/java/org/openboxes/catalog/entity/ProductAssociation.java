package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

// GET-only read entity (T9). Represents a directional association between two products (e.g. SUBSTITUTE,
// RELATED). Zero React callers (T1 audit §7: ProductAssociation verbs = "none") → FD#1 default GET-only.
// Read-entity shape: protected no-arg ctor + getters only (no setters), mirroring the T6/T7/T8 catalog
// read entities (ProductCatalogItem / ProductAttribute).
//
// WRITES DELIBERATELY REMAIN GRAILS-SIDE (forward-compat note). The Grails GSP
// ProductAssociationController is NOT being removed and continues to own create/update/delete. Therefore
// this T9 slice does NOT port:
//   - the ProductAssociationValidator (self-association reject + duplicate-association + duplicate-mutual
//     checks) — app-layer write validation stays Grails-side;
//   - the Grails domain `beforeDelete` cleanup that nulls the reciprocal mutualAssociation pointer on the
//     other ProductAssociation row when one side is deleted — delete cleanup stays Grails-side.
// A future write phase that moves create/update/delete here must port BOTH of those (validator +
// beforeDelete reciprocal-null) — recorded here so that future phase knows exactly what is outstanding.
//
// Audit-shape: timestamp-only listener per the T1 audit prescription and the T6/T7 canonical
// read-entity template. NO @CreatedBy/@LastModifiedBy — the Grails domain has only Instant
// dateCreated/lastUpdated (no User createdBy/updatedBy) and product_association has no
// created_by_id/updated_by_id columns. date_created/last_updated are both NOT NULL in the live schema.
// The AuditingEntityListener is INERT on the GET-only path today (no writes ever flow through this entity
// here) but is mapped per the canonical audit-shape so this stays forward-compatible if a write path is
// ever added. Uses Instant, mirroring ProductCatalogItem.java's audit block (the Grails domain also uses
// java.time.Instant for these fields).
//
// LIVE-SCHEMA DEVIATION FROM GRAILS (live schema is authoritative — a C3-class trap): the Grails
// constraint declares `quantity(nullable:true)`, but the live column `quantity decimal(19,2)` is NOT NULL
// (the Grails domain also field-defaults it to `= 0`). The live DB column wins → quantity is mapped
// @Column(nullable = false). Mapping it nullable would still validate, but nullable=false documents the
// real schema contract.
//
// FKs (all catalog-internal → @ManyToOne per FD#2, exposed as raw id strings by the DTO):
//   - product (belongsTo): product_id char(38) NOT NULL → optional=false.
//   - associatedProduct: associated_product_id char(38) NOT NULL → optional=false.
//   - mutualAssociation: SELF-FK referencing product_association itself; mutual_association_id char(38)
//     DEFAULT NULL → nullable @ManyToOne. The DTO reads .getId() on this self-proxy (id only, no init).
@Entity
@Table(name = "product_association")
@EntityListeners(AuditingEntityListener.class)
public class ProductAssociation {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column). Matches the canonical
    // template; the live column is NOT NULL.
    @Version
    private Long version;

    // The Grails enum ProductAssociationTypeCode is persisted by its name() as a String (the plan maps
    // "code as String"). code varchar(100) NOT NULL.
    @Column(name = "code", nullable = false)
    private String code;

    // belongsTo=[product:Product]. product_id char(38) NOT NULL → optional=false.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, columnDefinition = "CHAR(38)")
    private Product product;

    // associated_product_id char(38) NOT NULL (Grails associatedProduct(nullable:false)) → optional=false.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "associated_product_id", nullable = false, columnDefinition = "CHAR(38)")
    private Product associatedProduct;

    // LIVE-SCHEMA DEVIATION: quantity decimal(19,2) is NOT NULL in the live DB (Grails field-defaults = 0)
    // even though the Grails constraint says nullable:true. Live schema wins → nullable=false (see header).
    @Column(nullable = false)
    private BigDecimal quantity;

    // comments varchar(255) DEFAULT NULL (Grails comments(nullable:true)).
    @Column
    private String comments;

    // SELF-FK: references product_association itself. mutual_association_id char(38) DEFAULT NULL
    // (Grails mutualAssociation(nullable:true)). DTO reads .getId() off the proxy (id only, no init).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mutual_association_id", columnDefinition = "CHAR(38)")
    private ProductAssociation mutualAssociation;

    // Timestamp-only audit fields (NO User FK columns exist on product_association). Both NOT NULL.
    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // Read entity: protected no-arg ctor + getters only (mirrors ProductCatalogItem.java / ProductAttribute.java).
    protected ProductAssociation() {}

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getCode() { return code; }
    public Product getProduct() { return product; }
    public Product getAssociatedProduct() { return associatedProduct; }
    public BigDecimal getQuantity() { return quantity; }
    public String getComments() { return comments; }
    public ProductAssociation getMutualAssociation() { return mutualAssociation; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
