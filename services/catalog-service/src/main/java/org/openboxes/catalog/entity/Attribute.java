package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "attribute")
public class Attribute {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    private String code;
    @Column(nullable = false) private String name;
    private String description;

    private Boolean active = true;
    private Boolean exportable = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_of_measure_class_id", columnDefinition = "CHAR(38)")  // nullable
    private UnitOfMeasureClass unitOfMeasureClass;

    // Element-collection column names verified against live schema:
    //   attribute_options.options_string (+ options_idx ordering column)
    //   attribute_entity_type_codes.entity_type_code
    @ElementCollection
    @CollectionTable(name = "attribute_options", joinColumns = @JoinColumn(name = "attribute_id", columnDefinition = "CHAR(38)"))
    @OrderColumn(name = "options_idx")
    @Column(name = "options_string")
    private List<String> options;

    @Column(name = "default_value")
    private String defaultValue;
    private Boolean required = false;
    @Column(name = "allow_other")
    private Boolean allowOther;
    @Column(name = "allow_multiple")
    private Boolean allowMultiple = false;

    @ElementCollection
    @CollectionTable(name = "attribute_entity_type_codes", joinColumns = @JoinColumn(name = "attribute_id", columnDefinition = "CHAR(38)"))
    @Column(name = "entity_type_code")
    private List<String> entityTypeCodes;

    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    protected Attribute() {}

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Boolean getActive() { return active; }
    public Boolean getExportable() { return exportable; }
    public UnitOfMeasureClass getUnitOfMeasureClass() { return unitOfMeasureClass; }
    public List<String> getOptions() { return options; }
    public String getDefaultValue() { return defaultValue; }
    public Boolean getRequired() { return required; }
    public Boolean getAllowOther() { return allowOther; }
    public Boolean getAllowMultiple() { return allowMultiple; }
    public List<String> getEntityTypeCodes() { return entityTypeCodes; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
