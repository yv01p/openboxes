import { PRODUCT_SUPPLIER_PREFERENCE_API, PRODUCT_SUPPLIER_PREFERENCE_BATCH } from 'api/urls';
import apiClient from 'utils/apiClient';

export default {
  saveOrUpdateBatch: (payload) => apiClient.post(PRODUCT_SUPPLIER_PREFERENCE_BATCH, payload),
  getByProductSupplier: (productSupplierId) => apiClient.get(PRODUCT_SUPPLIER_PREFERENCE_API, {
    params: { productSupplier: productSupplierId },
  }),
};
