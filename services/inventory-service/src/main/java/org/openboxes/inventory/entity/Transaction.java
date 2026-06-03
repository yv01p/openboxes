package org.openboxes.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// `version` column is intentionally UNMAPPED.
// `transaction` is a SQL reserved word; backtick-quoted in @Table to be safe across queries.
@Entity
@Table(name = "`transaction`")
public class Transaction {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "created_by_id", columnDefinition = "CHAR(38)")
    private String createdById;
    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;
    @Column(name = "destination_id", columnDefinition = "CHAR(38)")
    private String destinationId;
    @Column(name = "inventory_id", columnDefinition = "CHAR(38)")
    private String inventoryId;
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
    @Column(name = "source_id", columnDefinition = "CHAR(38)")
    private String sourceId;
    @Column(name = "transaction_date", nullable = false)
    private Instant transactionDate;
    @Column(name = "transaction_type_id", columnDefinition = "CHAR(38)")
    private String transactionTypeId;
    private Boolean confirmed;
    @Column(name = "confirmed_by_id", columnDefinition = "CHAR(38)")
    private String confirmedById;
    @Column(name = "date_confirmed")
    private Instant dateConfirmed;
    private String comment;
    @Column(name = "incoming_shipment_id", columnDefinition = "CHAR(38)")
    private String incomingShipmentId;
    @Column(name = "outgoing_shipment_id", columnDefinition = "CHAR(38)")
    private String outgoingShipmentId;
    @Column(name = "updated_by_id", columnDefinition = "CHAR(38)")
    private String updatedById;
    @Column(name = "transaction_number")
    private String transactionNumber;
    @Column(name = "requisition_id", columnDefinition = "CHAR(38)")
    private String requisitionId;
    @Column(name = "order_id", columnDefinition = "CHAR(38)")
    private String orderId;
    @Column(name = "receipt_id", columnDefinition = "CHAR(38)")
    private String receiptId;
    @Column(name = "cycle_count_id", columnDefinition = "CHAR(38)")
    private String cycleCountId;
    @Column(name = "transaction_source_id", columnDefinition = "CHAR(38)")
    private String transactionSourceId;

    protected Transaction() {}

    public String getId() { return id; }
    public String getCreatedById() { return createdById; }
    public Instant getDateCreated() { return dateCreated; }
    public String getDestinationId() { return destinationId; }
    public String getInventoryId() { return inventoryId; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getSourceId() { return sourceId; }
    public Instant getTransactionDate() { return transactionDate; }
    public String getTransactionTypeId() { return transactionTypeId; }
    public Boolean getConfirmed() { return confirmed; }
    public String getConfirmedById() { return confirmedById; }
    public Instant getDateConfirmed() { return dateConfirmed; }
    public String getComment() { return comment; }
    public String getIncomingShipmentId() { return incomingShipmentId; }
    public String getOutgoingShipmentId() { return outgoingShipmentId; }
    public String getUpdatedById() { return updatedById; }
    public String getTransactionNumber() { return transactionNumber; }
    public String getRequisitionId() { return requisitionId; }
    public String getOrderId() { return orderId; }
    public String getReceiptId() { return receiptId; }
    public String getCycleCountId() { return cycleCountId; }
    public String getTransactionSourceId() { return transactionSourceId; }
}
