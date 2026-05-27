package org.openboxes.identity.entity;

import jakarta.persistence.*;

/**
 * Read-only minimal mapping for location table.
 * Identity-service uses this for 404/disabled checks and location.name population.
 * Identity-service NEVER writes to location (location-service is Phase 3-owned).
 */
@Entity
@Table(name = "location")
public class Location {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column
    private Boolean active;

    // Getters only (read-only entity)

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Boolean getActive() {
        return active;
    }
}
