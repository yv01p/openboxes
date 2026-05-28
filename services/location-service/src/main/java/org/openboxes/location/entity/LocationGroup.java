package org.openboxes.location.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "location_group")
public class LocationGroup {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(length = 255)
    private String name;

    @Column(name = "address_id", columnDefinition = "CHAR(38)")
    private String addressId;

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAddressId() { return addressId; }
}
