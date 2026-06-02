package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductSupplierListItemDto;

import java.util.List;

// Task LQ: bare domain result for the Product Sources list query. The service returns this;
// the controller assembles the {data, totalCount} transport map (layering parity with every
// other catalog-service endpoint, which builds Map.of("data", …) in the controller).
// Task LQ2: the element type is the ENRICHED ProductSupplierListItemDto (NOT the write/read
// ProductSupplierDto) — this result is used ONLY by the list path.
public record ProductSupplierListResult(List<ProductSupplierListItemDto> data, long totalCount) {
}
