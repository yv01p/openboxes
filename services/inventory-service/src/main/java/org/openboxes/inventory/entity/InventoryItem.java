package org.openboxes.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// `version` column is intentionally UNMAPPED.
@Entity
@Table(name = "inventory_item")
public class InventoryItem {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
    @Column(name = "lot_number")
    private String lotNumber;
    @Column(name = "product_id", columnDefinition = "CHAR(38)")
    private String productId;
    @Column(name = "expiration_date")
    private Instant expirationDate;
    private String comments;
    @Column(name = "lot_status")
    private String lotStatus;

    protected InventoryItem() {}

    public String getId() { return id; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getLotNumber() { return lotNumber; }
    public String getProductId() { return productId; }
    public Instant getExpirationDate() { return expirationDate; }
    public String getComments() { return comments; }
    public String getLotStatus() { return lotStatus; }
}
