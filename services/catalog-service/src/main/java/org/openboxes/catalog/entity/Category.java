package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.List;

// T12: extended to a full-CRUD write entity. Audit is TIMESTAMP-ONLY: the live category table has
// NO created_by_id/updated_by_id columns and the Grails Category domain has no User audit fields, so
// only @CreatedDate/@LastModifiedDate are mapped (NO @CreatedBy/@LastModifiedBy). This matches the
// T6/T7/T9/T10/T11 timestamp-only block, not ProductSupplier's full-User-audit block.
@Entity
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Table(name = "category")
@EntityListeners(AuditingEntityListener.class)
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

    // @Version maps version bigint(20) NOT NULL (GORM optimistic-lock column, NO DB default).
    // WITHOUT this, POST/PUT fail in production with "Field 'version' doesn't have a default value".
    // (The integration test uses ddl-auto=create so it would NOT catch a missing mapping — the
    // real-schema ddl-auto=validate proof + live POST is the catch.)
    @Version
    private Long version;

    // Timestamp-only audit (no User FK columns on the category table). NOT NULL in the real table,
    // so auditing MUST populate both on every write.
    @CreatedDate
    @Column(name = "date_created", nullable = false, updatable = false)
    private Instant dateCreated;
    @LastModifiedDate
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    // F20: Category.deleted is in Grails transients; NOT persisted; do NOT include here

    public Category() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Boolean getIsRoot() { return isRoot; }
    public void setIsRoot(Boolean isRoot) { this.isRoot = isRoot; }

    public Category getParentCategory() { return parentCategory; }
    public void setParentCategory(Category parentCategory) { this.parentCategory = parentCategory; }

    public List<Category> getCategories() { return categories; }

    public String getGlAccountId() { return glAccountId; }
    public void setGlAccountId(String glAccountId) { this.glAccountId = glAccountId; }

    public Long getVersion() { return version; }

    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
