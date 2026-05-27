package org.openboxes.identity.entity;

import jakarta.persistence.*;

/**
 * Maps to the location_role table.
 * Note: version is INT (not BIGINT like other entities).
 */
@Entity
@Table(name = "location_role")
public class LocationRole {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Version
    @Column
    private Integer version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(name = "location_id", columnDefinition = "CHAR(38)")
    private String locationId;

    @Column(name = "location_roles_idx")
    private Integer locationRolesIdx;

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public Integer getLocationRolesIdx() {
        return locationRolesIdx;
    }

    public void setLocationRolesIdx(Integer locationRolesIdx) {
        this.locationRolesIdx = locationRolesIdx;
    }
}
