import { PRODUCT_PRICE_BY_ID } from 'api/urls';
import apiClient from 'utils/apiClient';

export default {
  getById: (id) => apiClient.get(PRODUCT_PRICE_BY_ID(id)),
};
