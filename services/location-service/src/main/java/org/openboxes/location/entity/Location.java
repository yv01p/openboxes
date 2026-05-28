package org.openboxes.location.entity;

import jakarta.persistence.*;
import java.util.Set;

@Entity
@Table(name = "location")
public class Location {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "location_number", length = 255)
    private String locationNumber;

    @Column
    private Boolean active;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "parent_location_id", columnDefinition = "CHAR(38)")
    private String parentLocationId;

    @Column(name = "zone_id", columnDefinition = "CHAR(38)")
    private String zoneId;

    @Column(name = "location_group_id", columnDefinition = "CHAR(38)")
    private String locationGroupId;

    @Column(name = "organization_id", columnDefinition = "CHAR(38)")
    private String organizationId;

    @Column(name = "manager_id", columnDefinition = "CHAR(38)")
    private String managerId;

    @Column(name = "address_id", columnDefinition = "CHAR(38)")
    private String addressId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_type_id")
    private LocationType locationType;

    @ElementCollection
    @CollectionTable(
        name = "location_supported_activities",
        joinColumns = @JoinColumn(name = "location_id"))
    @Column(name = "supported_activities_string")
    private Set<String> supportedActivities;

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLocationNumber() { return locationNumber; }
    public Boolean getActive() { return active; }
    public Integer getSortOrder() { return sortOrder; }
    public String getParentLocationId() { return parentLocationId; }
    public String getZoneId() { return zoneId; }
    public String getLocationGroupId() { return locationGroupId; }
    public String getOrganizationId() { return organizationId; }
    public String getManagerId() { return managerId; }
    public String getAddressId() { return addressId; }
    public LocationType getLocationType() { return locationType; }
    public Set<String> getSupportedActivities() { return supportedActivities; }
}
