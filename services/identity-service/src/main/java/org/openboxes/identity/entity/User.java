package org.openboxes.identity.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;

/**
 * Extends Person via JOINED inheritance.
 * Maps to the user table (MariaDB reserved word - backticks required).
 * User table has no version, date_created, last_updated, or active columns - those are on Person.
 */
@Entity
@Table(name = "`user`")
@PrimaryKeyJoinColumn(name = "id")
public class User extends Person {

    @Column(nullable = false, unique = true, length = 255)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(length = 255)
    private String locale;

    @Column(length = 255)
    private String timezone;

    @Column(name = "last_login_date")
    private Instant lastLoginDate;

    @Column(name = "warehouse_id", columnDefinition = "CHAR(38)")
    private String warehouseId;

    @Column(name = "manager_id", columnDefinition = "CHAR(38)")
    private String managerId;

    @Column(name = "remember_last_location")
    private Boolean rememberLastLocation;

    @Lob
    @Column(columnDefinition = "MEDIUMBLOB")
    private byte[] photo;

    @Lob
    @Column(name = "dashboard_config", columnDefinition = "LONGBLOB")
    private byte[] dashboardConfig;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_role",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<LocationRole> locationRoles;

    // Getters and setters

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Instant getLastLoginDate() {
        return lastLoginDate;
    }

    public void setLastLoginDate(Instant lastLoginDate) {
        this.lastLoginDate = lastLoginDate;
    }

    public String getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(String warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getManagerId() {
        return managerId;
    }

    public void setManagerId(String managerId) {
        this.managerId = managerId;
    }

    public Boolean getRememberLastLocation() {
        return rememberLastLocation;
    }

    public void setRememberLastLocation(Boolean rememberLastLocation) {
        this.rememberLastLocation = rememberLastLocation;
    }

    public byte[] getPhoto() {
        return photo;
    }

    public void setPhoto(byte[] photo) {
        this.photo = photo;
    }

    public byte[] getDashboardConfig() {
        return dashboardConfig;
    }

    public void setDashboardConfig(byte[] dashboardConfig) {
        this.dashboardConfig = dashboardConfig;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public Set<LocationRole> getLocationRoles() {
        return locationRoles;
    }

    public void setLocationRoles(Set<LocationRole> locationRoles) {
        this.locationRoles = locationRoles;
    }
}
