package org.openboxes.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// `version` column is intentionally UNMAPPED (read-only; validate tolerates extra DB columns).
// NOTE: inventory has NO warehouse_id; the facility->inventory link lives on location.inventory_id (T4).
@Entity
@Table(name = "inventory")
public class Inventory {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "last_inventory_date")
    private Instant lastInventoryDate;
    @Column(name = "date_created")
    private Instant dateCreated;
    @Column(name = "last_updated")
    private Instant lastUpdated;

    protected Inventory() {}

    public String getId() { return id; }
    public Instant getLastInventoryDate() { return lastInventoryDate; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
