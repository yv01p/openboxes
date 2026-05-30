import queryString from 'query-string';

import {
  AVAILABLE_ITEMS,
  CATALOG_PRODUCT_API,
  GENERIC_API,
  INVENTORY_ITEM,
  LOT_NUMBERS_WITH_EXPIRATION_DATE,
  PRODUCT_API,
} from 'api/urls';
import apiClient from 'utils/apiClient';

export default {
  // Phase 5: routed to openboxes-catalog-service via `/api/product` (FD#12 basic list).
  // Other consumers below keep PRODUCT_API plural to reach Grails-stays actions.
  getProducts: (config) => apiClient.get(CATALOG_PRODUCT_API, config),
  getInventoryItem: (productId, lotNumber) => apiClient.get(INVENTORY_ITEM(productId, lotNumber)),
  // TODO: tech debt: Replace by the product api call instead of generic
  getProduct: (id) => apiClient.get(`${GENERIC_API}/product/${id}`),
  getLatestInventoryCountDate: (productIds) => apiClient.get(`${PRODUCT_API}/getLatestInventoryCountDate`, {
    params: {
      productIds,
    },
    paramsSerializer: (parameters) => queryString.stringify(parameters),
  }),
  getLotNumbersByProductIds: (productIds) =>
    apiClient.get(LOT_NUMBERS_WITH_EXPIRATION_DATE, {
      params: { productIds },
      paramsSerializer: (parameters) => queryString.stringify(parameters),
    }),
  availableItems: ({ locationId, productIds }) => apiClient.get(AVAILABLE_ITEMS, {
    params: {
      'product.id': productIds,
      'location.id': locationId,
    },
    paramsSerializer: (parameters) => queryString.stringify(parameters),

  }),
};
