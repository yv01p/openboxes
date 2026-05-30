package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "unit_of_measure")
public class UnitOfMeasure {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false) private String name;
    @Column(nullable = false, unique = true) private String code;
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uom_class_id", columnDefinition = "CHAR(38)")  // nullable per FD#11
    private UnitOfMeasureClass uomClass;

    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    protected UnitOfMeasure() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public UnitOfMeasureClass getUomClass() { return uomClass; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
