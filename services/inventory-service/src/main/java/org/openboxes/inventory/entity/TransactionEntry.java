package org.openboxes.inventory.entity;

import jakarta.persistence.*;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// `version` column is intentionally UNMAPPED. transaction_entries_idx is the list-index column
// in Grails; mapped here as a flat Integer (no collection ownership in the read-only slice).
@Entity
@Table(name = "transaction_entry")
public class TransactionEntry {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "inventory_item_id", columnDefinition = "CHAR(38)")
    private String inventoryItemId;
    @Column(nullable = false)
    private Integer quantity;
    @Column(name = "transaction_id", columnDefinition = "CHAR(38)")
    private String transactionId;
    private String comments;
    @Column(name = "transaction_entries_idx")
    private Integer transactionEntriesIdx;
    @Column(name = "bin_location_id", columnDefinition = "CHAR(38)")
    private String binLocationId;
    @Column(name = "product_id", columnDefinition = "CHAR(38)")
    private String productId;
    @Column(name = "reason_code")
    private String reasonCode;

    protected TransactionEntry() {}

    public String getId() { return id; }
    public String getInventoryItemId() { return inventoryItemId; }
    public Integer getQuantity() { return quantity; }
    public String getTransactionId() { return transactionId; }
    public String getComments() { return comments; }
    public Integer getTransactionEntriesIdx() { return transactionEntriesIdx; }
    public String getBinLocationId() { return binLocationId; }
    public String getProductId() { return productId; }
    public String getReasonCode() { return reasonCode; }
}
