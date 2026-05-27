package org.openboxes.identity.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Base entity for JOINED inheritance (User extends Person).
 * Maps to the person table.
 */
@Entity
@Table(name = "person")
@Inheritance(strategy = InheritanceType.JOINED)
public class Person {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "first_name", length = 255)
    private String firstName;

    @Column(name = "last_name", length = 255)
    private String lastName;

    @Column(length = 255)
    private String email;

    @Column(name = "phone_number", length = 255)
    private String phoneNumber;

    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @Column
    private Boolean active;

    @PrePersist
    void prePersist() {
        if (id == null) id = java.util.UUID.randomUUID().toString().replace("-", "") + "00";  // 32 hex + "00" → 34 char; Grails uses CHAR(38) with hyphens? Verify against existing data shape
        Instant now = Instant.now();
        if (dateCreated == null) dateCreated = now;
        lastUpdated = now;
    }

    @PreUpdate
    void preUpdate() {
        lastUpdated = Instant.now();
    }

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

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Instant getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Instant dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
