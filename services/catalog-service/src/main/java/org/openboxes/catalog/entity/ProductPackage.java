package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

// T4 ProductPackage — write-path entity per phase 5.5 write-contract reconciliation. Mirrors the
// T2/T3 canonical migration template (full audit via FD#8 Option-A: AuditingEntityListener +
// JwtAuditorAware, reusing the T2 infra). All FKs here are catalog-internal @ManyToOne associations
// (product, uom, productSupplier); audit createdBy/updatedBy are raw String ids per FD#2.
//
// Column set mapped against the LIVE openboxes-db (SHOW CREATE TABLE product_package); ddl-auto=validate
// runs against that real table in production.
//
// DELIBERATE, JUSTIFIED DEVIATION FROM THE PLAN — `product` is mapped NULLABLE.
//   The implementation plan's T4 delta said `product @ManyToOne(optional=false) via belongsTo`. That
//   is WRONG against ground truth: the live product_package.product_id column is `char(38) DEFAULT NULL`
//   (nullable), AND the Grails domain (ProductPackage.groovy:59) explicitly declares
//   `product(nullable: true, validator: {...})`. Per the codebase's "live schema authoritative"
//   principle, `product` is mapped as a NULLABLE @ManyToOne (no optional=false). The genuinely
//   NOT-NULL column is `quantity`.
//
// OMITTED at T4 (Java has no forward references; the entity doesn't exist yet):
//   - productPrice @ManyToOne ProductPrice (T5 appends; column product_price_id is nullable, so
//     ddl-auto=validate tolerates it being unmapped — same convention as T2's unmapped nullable columns).
@Entity
@Table(name = "product_package")
@EntityListeners(AuditingEntityListener.class)
public class ProductPackage {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // Name of product as it appears on the package; nullable varchar(255) per live schema + GORM.
    private String name;

    // Description of the package; varchar(255) per LIVE schema (NOT longtext — plain String, no
    // columnDefinition). Nullable per GORM.
    private String description;

    // Global trade identification number; nullable varchar(255).
    private String gtin;

    // Number of units (each) in the box; quantity int(11) NOT NULL — the genuinely required column.
    @Column(nullable = false)
    private Integer quantity;

    // Catalog-internal FK — @ManyToOne per FD#2. NULLABLE (see header deviation note above): live
    // column is DEFAULT NULL and the Grails domain declares product(nullable:true).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", columnDefinition = "CHAR(38)")
    private Product product;

    // Catalog-internal FK — @ManyToOne per FD#2. Nullable per GORM uom(nullable:true) + live schema.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uom_id", columnDefinition = "CHAR(38)")
    private UnitOfMeasure uom;

    // Catalog-internal FK (ProductSupplier from T2) — @ManyToOne. Nullable per GORM + live schema.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_supplier_id", columnDefinition = "CHAR(38)")
    private ProductSupplier productSupplier;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column, NO DB default).
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

    // public (not protected): write-path mappers in the dto package construct instances directly.
    public ProductPackage() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getGtin() { return gtin; }
    public void setGtin(String gtin) { this.gtin = gtin; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public UnitOfMeasure getUom() { return uom; }
    public void setUom(UnitOfMeasure uom) { this.uom = uom; }

    public ProductSupplier getProductSupplier() { return productSupplier; }
    public void setProductSupplier(ProductSupplier productSupplier) { this.productSupplier = productSupplier; }

    public Long getVersion() { return version; }

    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getCreatedById() { return createdById; }
    public String getUpdatedById() { return updatedById; }
}
