package org.openboxes.inventory.entity;

import jakarta.persistence.*;
import java.time.Instant;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// `version` column is intentionally UNMAPPED.
@Entity
@Table(name = "transaction_type")
public class TransactionType {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;
    private String description;
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
    @Column(nullable = false)
    private String name;
    @Column(name = "sort_order")
    private Integer sortOrder;
    @Column(name = "transaction_code", nullable = false)
    private String transactionCode;

    protected TransactionType() {}

    public String getId() { return id; }
    public Instant getDateCreated() { return dateCreated; }
    public String getDescription() { return description; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getName() { return name; }
    public Integer getSortOrder() { return sortOrder; }
    public String getTransactionCode() { return transactionCode; }
}
