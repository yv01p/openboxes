package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "tag")
public class Tag {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false) private String tag;
    @Column(name = "is_active", columnDefinition = "TINYINT") private Boolean isActive = true;

    // FD#9: Tag is inverse side; Product owns the M:N
    @ManyToMany(mappedBy = "tags", fetch = FetchType.LAZY)
    private Set<Product> products = new HashSet<>();

    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    protected Tag() {}

    public String getId() { return id; }
    public String getTag() { return tag; }
    public Boolean getIsActive() { return isActive; }
    public Set<Product> getProducts() { return products; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }

    // T1 audit confirmed Tag GET-only — no setters added
}
