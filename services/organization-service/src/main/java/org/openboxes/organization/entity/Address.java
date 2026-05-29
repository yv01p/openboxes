package org.openboxes.organization.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "address")
public class Address {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false, length = 255) private String address;
    @Column(length = 255) private String address2;
    @Column(length = 255) private String city;
    @Column(name = "state_or_province", length = 255) private String stateOrProvince;
    @Column(name = "postal_code", length = 255) private String postalCode;
    @Column(length = 255) private String country;
    @Column(length = 4000) private String description;
    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;

    public String getId() { return id; }
    public String getAddress() { return address; }
    public String getAddress2() { return address2; }
    public String getCity() { return city; }
    public String getStateOrProvince() { return stateOrProvince; }
    public String getPostalCode() { return postalCode; }
    public String getCountry() { return country; }
    public String getDescription() { return description; }
}
