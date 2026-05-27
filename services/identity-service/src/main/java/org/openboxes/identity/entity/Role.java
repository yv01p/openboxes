package org.openboxes.identity.entity;

import jakarta.persistence.*;

/**
 * Maps to the role table.
 */
@Entity
@Table(name = "role")
public class Role {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", length = 255, nullable = false)
    private RoleType roleType;

    @Column(length = 255, nullable = false)
    private String name;

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RoleType getRoleType() {
        return roleType;
    }

    public void setRoleType(RoleType roleType) {
        this.roleType = roleType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
