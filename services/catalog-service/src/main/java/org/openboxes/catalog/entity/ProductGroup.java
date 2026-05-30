package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "product_group")
public class ProductGroup {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false, unique = true) private String name;
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", columnDefinition = "CHAR(38)")  // nullable per ProductGroup.groovy `ignoreNotFound: true`
    private Category category;

    // M:N inverse side (Product owns via product_group_product)
    @ManyToMany(mappedBy = "productGroups", fetch = FetchType.LAZY)
    private Set<Product> products = new HashSet<>();

    // siblings: inverse of Product.productFamily (per F11)
    @OneToMany(mappedBy = "productFamily", fetch = FetchType.LAZY)
    private Set<Product> siblings = new HashSet<>();

    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    protected ProductGroup() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public Set<Product> getProducts() { return products; }
    public Set<Product> getSiblings() { return siblings; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
