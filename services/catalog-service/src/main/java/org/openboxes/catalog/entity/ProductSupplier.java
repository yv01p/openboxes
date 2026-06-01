package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

// First WRITE-path entity in catalog-service (T2). Full audit via FD#8 Option-A
// (AuditingEntityListener + JwtAuditorAware). Cross-service FKs (supplier, manufacturer →
// org-service; createdBy/updatedBy → identity-service) are raw String id columns per FD#2
// (NO @ManyToOne to non-catalog entities). Only the catalog-internal `product` FK is @ManyToOne.
//
// Column set mapped against the LIVE openboxes-db (SHOW CREATE TABLE product_supplier); ddl-auto=validate
// runs against that real table in production. Legacy columns unit_of_measure_id / unit_price / unit_cost
// exist in the table but are NOT in the Grails domain — left intentionally unmapped (all nullable, so
// validate ignores them and inserts that omit them succeed).
//
// T4 appended: defaultProductPackage @ManyToOne ProductPackage (column default_product_package_id).
// T5 appended: contractPrice @ManyToOne ProductPrice (column contract_price_id, nullable). Nothing
// remains omitted — all catalog-internal FK associations on product_supplier are now mapped.
@Entity
@Table(name = "product_supplier")
@EntityListeners(AuditingEntityListener.class)
public class ProductSupplier {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "longtext")
    private String description;

    @Column(name = "product_code")
    private String productCode;

    // Catalog-internal FK — @ManyToOne per FD#2.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, columnDefinition = "CHAR(38)")
    private Product product;

    private String ndc;
    private String upc;

    // Cross-service FK (org-service) — raw String id per FD#2 (NO @ManyToOne).
    @Column(name = "manufacturer_id", columnDefinition = "CHAR(38)")
    private String manufacturerId;
    @Column(name = "manufacturer_code")
    private String manufacturerCode;
    @Column(name = "manufacturer_name")
    private String manufacturerName;
    @Column(name = "brand_name")
    private String brandName;
    @Column(name = "model_number")
    private String modelNumber;

    // Cross-service FK (org-service) — raw String id per FD#2 (NO @ManyToOne).
    @Column(name = "supplier_id", columnDefinition = "CHAR(38)")
    private String supplierId;
    @Column(name = "supplier_code")
    private String supplierCode;
    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "rating_type_code")
    private String ratingTypeCode;  // RatingTypeCode enum stored as String (Phase-5 convention)

    @Column(name = "standard_lead_time_days")
    private BigDecimal standardLeadTimeDays;
    @Column(name = "min_order_quantity")
    private BigDecimal minOrderQuantity;

    private String comments;

    // NOT NULL in the real table; must never be null. Default false matches the Grails domain.
    @Column(name = "tiered_pricing", nullable = false)
    private Boolean tieredPricing = false;

    private Boolean active = true;

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column, NO DB default).
    // WITHOUT this, POST/PUT fail in production with "Field 'version' doesn't have a default value".
    // (The integration test uses ddl-auto=create so it would NOT catch a missing mapping — the e2e
    // POST against the real DB is the proof.)
    @Version
    private Long version;

    // FD#8 Option-A audit fields. created_by_id/updated_by_id are nullable char(38) (tolerate NULL
    // for anonymous/system writes); auditor id supplied by JwtAuditorAware (= JWT subject = user id).
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

    // T4 forward-decl split: catalog-internal FK → ProductPackage. Nullable @ManyToOne (live column
    // default_product_package_id char(38) DEFAULT NULL). Resolved by ProductSupplierService from the
    // DTO's defaultProductPackageId on every save/update.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_product_package_id", columnDefinition = "CHAR(38)")
    private ProductPackage defaultProductPackage;

    // T5 forward-decl split: catalog-internal FK → ProductPrice. Nullable @ManyToOne (live column
    // contract_price_id char(38) DEFAULT NULL). Resolved by ProductSupplierService from the DTO's
    // contractPriceId on every save/update, and set by ProductPackageService when the package POST
    // carries an embedded contract price (contractPricePrice).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_price_id", columnDefinition = "CHAR(38)")
    private ProductPrice contractPrice;

    // public (not protected like the R/O entities): write-path mappers in the dto package
    // (ProductSupplierDto.toEntity) construct instances directly.
    public ProductSupplier() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public String getNdc() { return ndc; }
    public void setNdc(String ndc) { this.ndc = ndc; }

    public String getUpc() { return upc; }
    public void setUpc(String upc) { this.upc = upc; }

    public String getManufacturerId() { return manufacturerId; }
    public void setManufacturerId(String manufacturerId) { this.manufacturerId = manufacturerId; }

    public String getManufacturerCode() { return manufacturerCode; }
    public void setManufacturerCode(String manufacturerCode) { this.manufacturerCode = manufacturerCode; }

    public String getManufacturerName() { return manufacturerName; }
    public void setManufacturerName(String manufacturerName) { this.manufacturerName = manufacturerName; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getModelNumber() { return modelNumber; }
    public void setModelNumber(String modelNumber) { this.modelNumber = modelNumber; }

    public String getSupplierId() { return supplierId; }
    public void setSupplierId(String supplierId) { this.supplierId = supplierId; }

    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    public String getRatingTypeCode() { return ratingTypeCode; }
    public void setRatingTypeCode(String ratingTypeCode) { this.ratingTypeCode = ratingTypeCode; }

    public BigDecimal getStandardLeadTimeDays() { return standardLeadTimeDays; }
    public void setStandardLeadTimeDays(BigDecimal standardLeadTimeDays) { this.standardLeadTimeDays = standardLeadTimeDays; }

    public BigDecimal getMinOrderQuantity() { return minOrderQuantity; }
    public void setMinOrderQuantity(BigDecimal minOrderQuantity) { this.minOrderQuantity = minOrderQuantity; }

    public String getComments() { return comments; }
    public void setComments(String comments) { this.comments = comments; }

    public Boolean getTieredPricing() { return tieredPricing; }
    public void setTieredPricing(Boolean tieredPricing) { this.tieredPricing = tieredPricing; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public Long getVersion() { return version; }

    public ProductPackage getDefaultProductPackage() { return defaultProductPackage; }
    public void setDefaultProductPackage(ProductPackage defaultProductPackage) { this.defaultProductPackage = defaultProductPackage; }

    public ProductPrice getContractPrice() { return contractPrice; }
    public void setContractPrice(ProductPrice contractPrice) { this.contractPrice = contractPrice; }

    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getCreatedById() { return createdById; }
    public String getUpdatedById() { return updatedById; }
}
