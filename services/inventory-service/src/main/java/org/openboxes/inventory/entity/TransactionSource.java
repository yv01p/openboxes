package org.openboxes.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// transaction_source has NO version column. accurate bit(1) -> Boolean.
@Entity
@Table(name = "transaction_source")
public class TransactionSource {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "transaction_action", nullable = false)
    private String transactionAction;
    @Column(name = "shipment_id", columnDefinition = "CHAR(38)")
    private String shipmentId;
    @Column(name = "requisition_id", columnDefinition = "CHAR(38)")
    private String requisitionId;
    @Column(name = "receipt_id", columnDefinition = "CHAR(38)")
    private String receiptId;
    @Column(name = "order_id", columnDefinition = "CHAR(38)")
    private String orderId;
    @Column(name = "cycle_count_id", columnDefinition = "CHAR(38)")
    private String cycleCountId;
    @Column(name = "origin_id", columnDefinition = "CHAR(38)")
    private String originId;
    @Column(name = "destination_id", columnDefinition = "CHAR(38)")
    private String destinationId;
    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
    @Column(name = "created_by_id", columnDefinition = "CHAR(38)", nullable = false)
    private String createdById;
    @Column(name = "updated_by_id", columnDefinition = "CHAR(38)", nullable = false)
    private String updatedById;
    private Boolean accurate;

    protected TransactionSource() {}

    public String getId() { return id; }
    public String getTransactionAction() { return transactionAction; }
    public String getShipmentId() { return shipmentId; }
    public String getRequisitionId() { return requisitionId; }
    public String getReceiptId() { return receiptId; }
    public String getOrderId() { return orderId; }
    public String getCycleCountId() { return cycleCountId; }
    public String getOriginId() { return originId; }
    public String getDestinationId() { return destinationId; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getCreatedById() { return createdById; }
    public String getUpdatedById() { return updatedById; }
    public Boolean getAccurate() { return accurate; }
}
