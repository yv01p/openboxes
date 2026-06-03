package org.openboxes.inventory.service;

import org.openboxes.inventory.client.CatalogReadClient;
import org.openboxes.inventory.dto.ProductClassificationDto;
import org.openboxes.inventory.repository.InventoryLevelRepository;
import org.openboxes.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.TreeSet;

@Service
@Transactional(readOnly = true)
public class ProductClassificationService {
    private final InventoryRepository inventoryRepo;
    private final InventoryLevelRepository levelRepo;
    private final CatalogReadClient catalogClient;

    public ProductClassificationService(InventoryRepository inventoryRepo,
                                        InventoryLevelRepository levelRepo,
                                        CatalogReadClient catalogClient) {
        this.inventoryRepo = inventoryRepo;
        this.levelRepo = levelRepo;
        this.catalogClient = catalogClient;
    }

    // Behavior-preserving port of Grails ProductClassificationService.list(). The unhandled
    // IllegalArgumentException below surfaces as Spring's default 500 — this intentionally preserves
    // the existing error contract (Grails threw on a null Location.read), so NO @ExceptionHandler is
    // added (YAGNI). Empty-string filtering lives in the InventoryLevel query (<> '') and on the catalog
    // side (<> ''); both null and '' are excluded. Sort + dedup come from the TreeSet (== Grails .sort()).
    public List<ProductClassificationDto> list(String facilityId, String obxToken) {
        // Guard (matches Grails Location.read(facilityId) == null -> throw): unknown facility -> 500.
        if (inventoryRepo.countLocationById(facilityId) == 0L) {
            throw new IllegalArgumentException("Invalid facilityId: " + facilityId);
        }
        // TreeSet => dedup + alphabetical sort (matches Grails .sort()).
        TreeSet<String> classes = new TreeSet<>();
        // Global Product.abcClass (from catalog-service over HTTP, forwarding the caller's token).
        classes.addAll(catalogClient.distinctAbcClasses(obxToken));
        // Facility-scoped InventoryLevel.abcClass. The facility->inventory link is location.inventory_id
        // (Option B native read). A facility with no inventory (null inventory_id) contributes nothing —
        // matches Grails eq("inventory", facility.inventory) with a null inventory (NOT an error).
        String inventoryId = inventoryRepo.findInventoryIdByFacility(facilityId);
        if (inventoryId != null) {
            classes.addAll(levelRepo.findDistinctAbcClassesByInventoryId(inventoryId));
        }
        return classes.stream().map(ProductClassificationDto::new).toList();
    }
}
