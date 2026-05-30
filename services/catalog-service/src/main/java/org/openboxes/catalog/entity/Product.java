package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// READ-ONLY per FD#1 (no setter methods exposed); R/O enforcement is via ProductService
// being @Transactional(readOnly = true). @Immutable is intentionally NOT applied — it would
// suppress owned-collection writes on product_tag (Tag M:N owning side per FD#9), breaking
// TagService writes when T1 option (c) is selected (CIR R1 §2.1).
@Entity
@Table(name = "product")
public class Product {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false)
    private String name;
    private String description;
    @Column(name = "product_code")
    private String productCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_type_id", columnDefinition = "CHAR(38)")
    private ProductType productType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", columnDefinition = "CHAR(38)")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_of_measure_id", columnDefinition = "CHAR(38)")
    private UnitOfMeasure unitOfMeasure;

    @Column(name = "price_per_unit")
    private BigDecimal pricePerUnit;
    @Column(name = "cost_per_unit")
    private BigDecimal costPerUnit;
    private Boolean active;

    // FD#9: Product owns the M:N relationship to Tag via product_tag join table
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "product_tag",
        joinColumns = @JoinColumn(name = "product_id", columnDefinition = "CHAR(38)"),
        inverseJoinColumns = @JoinColumn(name = "tag_id", columnDefinition = "CHAR(38)")
    )
    private Set<Tag> tags = new HashSet<>();

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    private List<Synonym> synonyms;

    // M:N to ProductGroup via product_group_product (per F11)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "product_group_product",
        joinColumns = @JoinColumn(name = "product_id", columnDefinition = "CHAR(38)"),
        inverseJoinColumns = @JoinColumn(name = "product_group_id", columnDefinition = "CHAR(38)")
    )
    private Set<ProductGroup> productGroups = new HashSet<>();

    // Product has FK productFamily to ProductGroup (per F11; mappedBy "productFamily" in ProductGroup.siblings)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_family_id", columnDefinition = "CHAR(38)")
    private ProductGroup productFamily;

    @Column(name = "date_created")
    private Instant dateCreated;
    @Column(name = "last_updated")
    private Instant lastUpdated;

    protected Product() {}

    // getters only (R/O entity)
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getProductCode() { return productCode; }
    public ProductType getProductType() { return productType; }
    public Category getCategory() { return category; }
    public UnitOfMeasure getUnitOfMeasure() { return unitOfMeasure; }
    public BigDecimal getPricePerUnit() { return pricePerUnit; }
    public BigDecimal getCostPerUnit() { return costPerUnit; }
    public Boolean getActive() { return active; }
    public Set<Tag> getTags() { return tags; }
    public List<Synonym> getSynonyms() { return synonyms; }
    public Set<ProductGroup> getProductGroups() { return productGroups; }
    public ProductGroup getProductFamily() { return productFamily; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
