package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

// T5 ProductPrice — write-path entity per phase 5.5 write-contract reconciliation. Mirrors the
// T2/T3/T4 canonical migration template (full audit via FD#8 Option-A: AuditingEntityListener +
// JwtAuditorAware, reusing the T2 infra). The only @ManyToOne here is the catalog-internal `currency`
// (UnitOfMeasure); audit createdBy/updatedBy are raw String ids per FD#2.
//
// Column set mapped against the LIVE openboxes-db (SHOW CREATE TABLE product_price); ddl-auto=validate
// runs against that real table in production.
//
// DEVIATION #1 (authoritative live-schema ground truth, overrides the plan/PV11):
//   ProductPrice has NO productPackage / productSupplier associations. The plan's T5 delta and PV11
//   claimed "Entity FKs: productPackage, productSupplier, currency (all nullable @ManyToOne)" — that is
//   WRONG against the live DDL. The live `product_price` table has ONLY `currency_id` as an FK column;
//   there are NO `product_package_id` / `product_supplier_id` columns. The relationship is owned from
//   the OTHER side: `product_package.product_price_id` and `product_supplier.contract_price_id` point
//   TO product_price (mapped as @ManyToOne ProductPrice on ProductPackage / ProductSupplier at T5).
//   Therefore `currency` is the only @ManyToOne; do NOT add productPackage/productSupplier here.
//
// DEVIATION #2 (note): the React form reads `contractPrice.validUntil` (PV16), but the live table has
//   `from_date`/`to_date`, NOT `valid_until`. The form's `contractPriceValidUntil` corresponds to
//   `to_date` (the form-side wiring is a CUT concern). There is NO `valid_until` column — confirmed
//   against the live schema. We map `fromDate`/`toDate`; we do NOT invent a `validUntil` column.
//
// TRAP — `type` is NOT NULL with NO DB default. The Grails domain declares
//   `PriceTypeCode type = PriceTypeCode.DEFAULT_PRICE`, stored as its name() (Phase-5 enum-as-String
//   convention, like ProductSupplier.ratingTypeCode). The field is initialized to "DEFAULT_PRICE" so
//   every insert populates it regardless of code path (analogous to T2's tieredPricing = false).
@Entity
@Table(name = "product_price")
@EntityListeners(AuditingEntityListener.class)
public class ProductPrice {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column, NO DB default).
    @Version
    private Long version;

    // PriceTypeCode enum stored as String (Phase-5 convention). NOT NULL with NO DB default — see the
    // TRAP note above; initialized to "DEFAULT_PRICE" so every insert populates it.
    @Column(nullable = false)
    private String type = "DEFAULT_PRICE";

    // price decimal(19,4) NOT NULL — the genuinely required value column.
    @Column(nullable = false)
    private BigDecimal price;

    // The ONLY FK column on this table — catalog-internal @ManyToOne UnitOfMeasure. Nullable per GORM
    // currency(nullable:true) + live schema (currency_id char(38) DEFAULT NULL).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", columnDefinition = "CHAR(38)")
    private UnitOfMeasure currency;

    // from_date / to_date datetime DEFAULT NULL. `to_date` is what the React form calls
    // `contractPriceValidUntil` (Deviation #2).
    @Column(name = "from_date")
    private Instant fromDate;
    @Column(name = "to_date")
    private Instant toDate;

    // FD#8 Option-A audit fields. NOTE (unlike T2/T3/T4): date_created/last_updated are NULLABLE here
    // to match the live product_price schema (`date_created`/`last_updated` datetime DEFAULT NULL) —
    // the nullable=false used on the date columns of the other entities is DROPPED here.
    @CreatedDate
    @Column(name = "date_created", updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated")
    private Instant lastUpdated;
    @CreatedBy
    @Column(name = "created_by_id", columnDefinition = "CHAR(38)", updatable = false)
    private String createdById;
    @LastModifiedBy
    @Column(name = "updated_by_id", columnDefinition = "CHAR(38)")
    private String updatedById;

    // public (not protected): write-path mappers in the dto package construct instances directly.
    public ProductPrice() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public UnitOfMeasure getCurrency() { return currency; }
    public void setCurrency(UnitOfMeasure currency) { this.currency = currency; }

    public Instant getFromDate() { return fromDate; }
    public void setFromDate(Instant fromDate) { this.fromDate = fromDate; }

    public Instant getToDate() { return toDate; }
    public void setToDate(Instant toDate) { this.toDate = toDate; }

    public Long getVersion() { return version; }

    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getCreatedById() { return createdById; }
    public String getUpdatedById() { return updatedById; }
}
