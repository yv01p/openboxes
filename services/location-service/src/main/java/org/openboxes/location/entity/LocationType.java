package org.openboxes.location.entity;

import jakarta.persistence.*;
import org.openboxes.location.enums.LocationTypeCode;
import java.util.Set;

@Entity
@Table(name = "location_type")
public class LocationType {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "location_type_code", length = 100)
    @Enumerated(EnumType.STRING)
    private LocationTypeCode locationTypeCode;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @ElementCollection
    @CollectionTable(
        name = "location_type_supported_activities",
        joinColumns = @JoinColumn(name = "location_type_id"))
    @Column(name = "supported_activities_string")
    private Set<String> supportedActivities;

    // Getters only (read-only entity)
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public LocationTypeCode getLocationTypeCode() { return locationTypeCode; }
    public Integer getSortOrder() { return sortOrder; }
    public Set<String> getSupportedActivities() { return supportedActivities; }
}
