package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "unit_of_measure_class")
public class UnitOfMeasureClass {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false) private String name;
    @Column(nullable = false, unique = true) private String code;
    private String description;
    private Boolean active;

    @Column(name = "type", nullable = false)
    private String type;  // UnitOfMeasureType enum stored as String

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_uom_id", columnDefinition = "CHAR(38)")  // nullable per FD#11
    private UnitOfMeasure baseUom;

    @OneToMany(mappedBy = "uomClass", fetch = FetchType.LAZY)
    private List<UnitOfMeasure> uoms;

    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    protected UnitOfMeasureClass() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public Boolean getActive() { return active; }
    public String getType() { return type; }
    public UnitOfMeasure getBaseUom() { return baseUom; }
    public List<UnitOfMeasure> getUoms() { return uoms; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
