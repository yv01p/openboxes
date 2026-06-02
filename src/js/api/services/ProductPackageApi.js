import { PRODUCT_PACKAGE_API, PRODUCT_PACKAGE_BY_ID } from 'api/urls';
import apiClient from 'utils/apiClient';

export default {
  save: (payload) => apiClient.post(PRODUCT_PACKAGE_API, payload),
  getById: (id) => apiClient.get(PRODUCT_PACKAGE_BY_ID(id)),
};
