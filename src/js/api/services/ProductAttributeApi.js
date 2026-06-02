import { PRODUCT_ATTRIBUTE_API } from 'api/urls';
import apiClient from 'utils/apiClient';

export default {
  // catalog-service GET-only ProductAttribute rows (flat: { id, productId, attributeId, value,
  // unitOfMeasureId, productSupplierId }), filtered SERVER-SIDE to one product supplier's saved
  // attribute values (the flat productSupplier DTO no longer nests them). The cutover form-load
  // uses this to recover a supplier's attributes without fetching the whole table.
  getByProductSupplier: (productSupplierId) =>
    apiClient.get(PRODUCT_ATTRIBUTE_API, { params: { productSupplier: productSupplierId } }),
};
