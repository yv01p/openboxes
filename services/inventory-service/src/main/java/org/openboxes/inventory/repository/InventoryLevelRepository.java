package org.openboxes.inventory.repository;

import org.openboxes.inventory.entity.InventoryLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryLevelRepository extends JpaRepository<InventoryLevel, String> {

    @Query("select distinct il.abcClass from InventoryLevel il " +
           "where il.inventoryId = :inventoryId and il.abcClass is not null and il.abcClass <> ''")
    java.util.List<String> findDistinctAbcClassesByInventoryId(@Param("inventoryId") String inventoryId);
}
