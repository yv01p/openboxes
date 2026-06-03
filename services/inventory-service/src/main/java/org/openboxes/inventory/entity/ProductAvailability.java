package org.openboxes.inventory.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Formula;
import java.time.Instant;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// `version` column is intentionally UNMAPPED.
// Note the int vs bigint split: quantity_on_hand/quantity_allocated are int -> Integer;
// quantity_on_hold/quantity_available_to_promise are bigint -> Long.
@Entity
@Table(name = "product_availability")
public class ProductAvailability {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "bin_location_id", columnDefinition = "CHAR(38)")
    private String binLocationId;
    @Column(name = "inventory_item_id", columnDefinition = "CHAR(38)", nullable = false)
    private String inventoryItemId;
    @Column(name = "location_id", columnDefinition = "CHAR(38)", nullable = false)
    private String locationId;
    @Column(name = "product_id", columnDefinition = "CHAR(38)", nullable = false)
    private String productId;
    @Column(name = "product_code", nullable = false)
    private String productCode;
    @Column(name = "lot_number", nullable = false)
    private String lotNumber;
    @Column(name = "bin_location_name", nullable = false)
    private String binLocationName;
    @Column(name = "quantity_allocated")
    private Integer quantityAllocated;
    @Column(name = "quantity_on_hand", nullable = false)
    private Integer quantityOnHand;
    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
    @Column(name = "quantity_on_hold")
    private Long quantityOnHold;
    @Column(name = "quantity_available_to_promise")
    private Long quantityAvailableToPromise;

    // Not a real column — derived. @Formula keeps validate happy (no DB column expected).
    @Formula("quantity_on_hand - quantity_allocated")
    private Integer quantityNotPicked;

    protected ProductAvailability() {}

    public String getId() { return id; }
    public String getBinLocationId() { return binLocationId; }
    public String getInventoryItemId() { return inventoryItemId; }
    public String getLocationId() { return locationId; }
    public String getProductId() { return productId; }
    public String getProductCode() { return productCode; }
    public String getLotNumber() { return lotNumber; }
    public String getBinLocationName() { return binLocationName; }
    public Integer getQuantityAllocated() { return quantityAllocated; }
    public Integer getQuantityOnHand() { return quantityOnHand; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public Long getQuantityOnHold() { return quantityOnHold; }
    public Long getQuantityAvailableToPromise() { return quantityAvailableToPromise; }
    public Integer getQuantityNotPicked() { return quantityNotPicked; }
}
