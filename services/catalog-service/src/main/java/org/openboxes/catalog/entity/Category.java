package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import java.time.Instant;
import java.util.List;

@Entity
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Table(name = "category")
public class Category {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false)
    private String name;
    private String description;
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
    @Column(name = "is_root", columnDefinition = "TINYINT")
    private Boolean isRoot = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_category_id", columnDefinition = "CHAR(38)")
    private Category parentCategory;

    @OneToMany(mappedBy = "parentCategory", fetch = FetchType.LAZY)
    private List<Category> categories;

    // Note: GlAccount entity not yet ported (catalog-service does not depend on GlAccount entity);
    // exposed as flat FK string at DTO level. Plan-time decision: store as raw String FK.
    @Column(name = "gl_account_id", columnDefinition = "CHAR(38)")
    private String glAccountId;

    @Column(name = "date_created")
    private Instant dateCreated;
    @Column(name = "last_updated")
    private Instant lastUpdated;

    // F20: Category.deleted is in Grails transients; NOT persisted; do NOT include here

    protected Category() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Integer getSortOrder() { return sortOrder; }
    public Boolean getIsRoot() { return isRoot; }
    public Category getParentCategory() { return parentCategory; }
    public List<Category> getCategories() { return categories; }
    public String getGlAccountId() { return glAccountId; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    // setters added when CategoryService write path is implemented per T1 scope
}
