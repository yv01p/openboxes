package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductSupplierDto;

import java.util.List;

// Task LQ: bare domain result for the Product Sources list query. The service returns this;
// the controller assembles the {data, totalCount} transport map (layering parity with every
// other catalog-service endpoint, which builds Map.of("data", …) in the controller).
public record ProductSupplierListResult(List<ProductSupplierDto> data, long totalCount) {
}
