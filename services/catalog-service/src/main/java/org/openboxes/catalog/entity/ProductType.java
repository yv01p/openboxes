package org.openboxes.catalog.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "product_type")
public class ProductType {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false) private String name;
    private String code;

    @Column(name = "product_type_code", nullable = false)
    private String productTypeCode;  // ProductTypeCode enum stored as String

    @Column(name = "product_identifier_format")
    private String productIdentifierFormat;
    @Column(name = "sequence_number")
    private Integer sequenceNumber = 0;

    // Element-collection column names verified against live schema:
    //   product_type_supported_activities.product_activity_code
    //   product_type_required_fields.product_field
    //   product_type_displayed_fields.product_field
    @ElementCollection
    @CollectionTable(name = "product_type_supported_activities", joinColumns = @JoinColumn(name = "product_type_id", columnDefinition = "CHAR(38)"))
    @Column(name = "product_activity_code")
    private List<String> supportedActivities;

    @ElementCollection
    @CollectionTable(name = "product_type_required_fields", joinColumns = @JoinColumn(name = "product_type_id", columnDefinition = "CHAR(38)"))
    @Column(name = "product_field")
    private List<String> requiredFields;

    @ElementCollection
    @CollectionTable(name = "product_type_displayed_fields", joinColumns = @JoinColumn(name = "product_type_id", columnDefinition = "CHAR(38)"))
    @Column(name = "product_field")
    private List<String> displayedFields;

    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    protected ProductType() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getProductTypeCode() { return productTypeCode; }
    public String getProductIdentifierFormat() { return productIdentifierFormat; }
    public Integer getSequenceNumber() { return sequenceNumber; }
    public List<String> getSupportedActivities() { return supportedActivities; }
    public List<String> getRequiredFields() { return requiredFields; }
    public List<String> getDisplayedFields() { return displayedFields; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
