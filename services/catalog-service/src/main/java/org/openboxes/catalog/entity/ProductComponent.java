package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

// GET-only read entity (T10). It is a bill-of-materials line: a component product (with quantity + uom)
// belonging to an assembly product. Zero React callers (T1 audit §7: ProductComponent verbs = "none") →
// FD#1 default GET-only. Read-entity shape: protected no-arg ctor + getters only (no setters), mirroring
// the T6–T9 catalog read entities (ProductCatalogItem / ProductAttribute / ProductAssociation).
//
// WRITES DELIBERATELY REMAIN GRAILS-SIDE. The Grails GSP ProductController is NOT being removed and
// continues to own create/update/delete of product components. There is NO validator / no special-case
// to port (the Grails domain has only the four nullable:false constraints, all matching the live NOT NULL
// columns) — so a future write phase need only port the standard write template.
//
// Audit-shape: timestamp-only listener per the T1 audit prescription and the T6/T7/T9 canonical
// read-entity template. NO @CreatedBy/@LastModifiedBy — the Grails domain has only dateCreated/lastUpdated
// (no User createdBy/updatedBy) and product_component has no created_by_id/updated_by_id columns.
// date_created/last_updated are both NOT NULL in the live schema. The AuditingEntityListener is INERT on
// the GET-only path today (no writes ever flow through this entity here) but is mapped per the canonical
// audit-shape so this stays forward-compatible if a write path is ever added.
//
// TYPE NOTE: the Grails domain declares dateCreated/lastUpdated as java.util.Date, but they are mapped
// here as java.time.Instant to match the established T6/T7/T9 catalog-service convention (both map to the
// `datetime` column; AuditingEntityListener supports both; Instant gives uniform ISO-8601 JSON
// serialization across all catalog read DTOs).
//
// FKs (all catalog-internal → @ManyToOne per FD#2, exposed as raw id strings by the DTO). All three are
// NOT NULL in the live schema (matching the Grails nullable:false constraints — NO Grails-vs-live
// deviation) → optional=false:
//   - assemblyProduct (Grails belongsTo): assembly_product_id char(38) NOT NULL.
//   - componentProduct: component_product_id char(38) NOT NULL.
//   - unitOfMeasure: unit_of_measure_id char(38) NOT NULL.
//
// product_components_idx INT (a Grails indexed-collection ordering artifact, same as ProductAttribute's
// attributes_idx) is intentionally left UNMAPPED; ddl-auto=validate tolerates unmapped columns and T10
// does not own that ordering concern.
@Entity
@Table(name = "product_component")
@EntityListeners(AuditingEntityListener.class)
public class ProductComponent {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column). Matches the canonical
    // template; the live column is NOT NULL.
    @Version
    private Long version;

    // belongsTo=[assemblyProduct:Product]. assembly_product_id char(38) NOT NULL → optional=false.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assembly_product_id", nullable = false, columnDefinition = "CHAR(38)")
    private Product assemblyProduct;

    // component_product_id char(38) NOT NULL (Grails componentProduct(nullable:false)) → optional=false.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_product_id", nullable = false, columnDefinition = "CHAR(38)")
    private Product componentProduct;

    // quantity decimal(19,2) NOT NULL (matches Grails quantity(nullable:false) — NO deviation).
    @Column(nullable = false)
    private BigDecimal quantity;

    // unit_of_measure_id char(38) NOT NULL (Grails unitOfMeasure(nullable:false)) → optional=false.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_of_measure_id", nullable = false, columnDefinition = "CHAR(38)")
    private UnitOfMeasure unitOfMeasure;

    // Timestamp-only audit fields (NO User FK columns exist on product_component). Both NOT NULL.
    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // product_components_idx INT (Grails list-index artifact) is intentionally UNMAPPED.

    // Read entity: protected no-arg ctor + getters only (mirrors ProductCatalogItem.java / ProductAssociation.java).
    protected ProductComponent() {}

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public Product getAssemblyProduct() { return assemblyProduct; }
    public Product getComponentProduct() { return componentProduct; }
    public BigDecimal getQuantity() { return quantity; }
    public UnitOfMeasure getUnitOfMeasure() { return unitOfMeasure; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
