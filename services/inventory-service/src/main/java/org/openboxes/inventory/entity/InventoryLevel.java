package org.openboxes.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

// READ-ONLY (getters only). Flat FK-id columns only per FD#6 — no @ManyToOne / collections.
// `version` column is intentionally UNMAPPED.
@Entity
@Table(name = "inventory_level")
public class InventoryLevel {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(name = "inventory_id", columnDefinition = "CHAR(38)")
    private String inventoryId;
    @Column(name = "min_quantity")
    private Integer minQuantity;
    @Column(name = "product_id", columnDefinition = "CHAR(38)")
    private String productId;
    @Column(name = "reorder_quantity")
    private Integer reorderQuantity;
    @Column(name = "date_created")
    private Instant dateCreated;
    @Column(name = "last_updated")
    private Instant lastUpdated;
    private String status;
    @Column(name = "max_quantity")
    private Integer maxQuantity;
    @Column(name = "bin_location")
    private String binLocation;
    @Column(name = "abc_class")
    private String abcClass;
    // preferred is tinyint(3) in the live DB (not bit(1)); columnDefinition keeps validate aligned.
    @Column(columnDefinition = "TINYINT")
    private Boolean preferred;
    @Column(name = "expected_lead_time_days")
    private BigDecimal expectedLeadTimeDays;
    @Column(name = "forecast_period_days")
    private BigDecimal forecastPeriodDays;
    @Column(name = "forecast_quantity")
    private BigDecimal forecastQuantity;
    @Column(name = "preferred_bin_location_id", columnDefinition = "CHAR(38)")
    private String preferredBinLocationId;
    @Column(name = "replenishment_location_id", columnDefinition = "CHAR(38)")
    private String replenishmentLocationId;
    private String comments;
    @Column(name = "internal_location_id", columnDefinition = "CHAR(38)")
    private String internalLocationId;
    @Column(name = "replenishment_period_days")
    private BigDecimal replenishmentPeriodDays;
    @Column(name = "demand_time_period_days")
    private BigDecimal demandTimePeriodDays;

    protected InventoryLevel() {}

    public String getId() { return id; }
    public String getInventoryId() { return inventoryId; }
    public Integer getMinQuantity() { return minQuantity; }
    public String getProductId() { return productId; }
    public Integer getReorderQuantity() { return reorderQuantity; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
    public String getStatus() { return status; }
    public Integer getMaxQuantity() { return maxQuantity; }
    public String getBinLocation() { return binLocation; }
    public String getAbcClass() { return abcClass; }
    public Boolean getPreferred() { return preferred; }
    public BigDecimal getExpectedLeadTimeDays() { return expectedLeadTimeDays; }
    public BigDecimal getForecastPeriodDays() { return forecastPeriodDays; }
    public BigDecimal getForecastQuantity() { return forecastQuantity; }
    public String getPreferredBinLocationId() { return preferredBinLocationId; }
    public String getReplenishmentLocationId() { return replenishmentLocationId; }
    public String getComments() { return comments; }
    public String getInternalLocationId() { return internalLocationId; }
    public BigDecimal getReplenishmentPeriodDays() { return replenishmentPeriodDays; }
    public BigDecimal getDemandTimePeriodDays() { return demandTimePeriodDays; }
}
