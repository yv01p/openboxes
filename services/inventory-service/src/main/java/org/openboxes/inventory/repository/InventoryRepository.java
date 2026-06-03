package org.openboxes.inventory.repository;

import org.openboxes.inventory.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, String> {

    // Option B (FD#10 transitional cross-context read): the facility->inventory link lives on
    // location.inventory_id — the inventory table has no warehouse FK (DESCRIBE-first, Plan correction).
    // We read it via native SQL rather than mapping a cross-context Location entity (FD#6). This becomes
    // an HTTP call to location-service when that service is fully extracted (Phase 7/8). Tech-debt: T10 retro.

    @Query(value = "SELECT COUNT(*) FROM location WHERE id = :facilityId", nativeQuery = true)
    long countLocationById(@Param("facilityId") String facilityId);

    @Query(value = "SELECT inventory_id FROM location WHERE id = :facilityId", nativeQuery = true)
    String findInventoryIdByFacility(@Param("facilityId") String facilityId);
}
