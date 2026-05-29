package org.openboxes.organization.entity;

import jakarta.persistence.*;

@Entity
@DiscriminatorValue("org.pih.warehouse.core.Organization")
public class Organization extends Party {

    @Column(length = 255)
    private String code;

    @Column(length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "default_location_id", columnDefinition = "CHAR(38)")
    private String defaultLocationId;

    @Column(columnDefinition = "BIT(1)")
    private Boolean active = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDefaultLocationId() { return defaultLocationId; }
    public void setDefaultLocationId(String defaultLocationId) { this.defaultLocationId = defaultLocationId; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
