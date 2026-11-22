import axios from 'axios';

// Relative: goes through the gateway (K8s) or nginx (compose) to store's /api.
const API_BASE_URL = '/api/store';

// Since auth PR 2 these store endpoints need the login token (the admin role check comes in PR 3).
// Its own axios instance with the same header the rest of the app sends (services/api.js).
const adminApi = axios.create();
adminApi.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
// Expired or missing token: same as the rest of the app (services/api.js), log out and go to the log-in page
adminApi.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response && error.response.status === 401) {
      ['token', 'userEmail', 'userId', 'role', 'isLoggedIn'].forEach((key) => localStorage.removeItem(key));
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

const adminService = {
  // Order Module - Get all orders
  getAllOrders: async () => {
    try {
      const response = await adminApi.get(`${API_BASE_URL}/orders`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Product Module - Get all products
  getAllProducts: async () => {
    try {
      const response = await adminApi.get(`${API_BASE_URL}/products`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Warehouse Module - Get all warehouses
  getAllWarehouses: async () => {
    try {
      const response = await adminApi.get(`${API_BASE_URL}/warehouses`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Inventory Audit Report - Get all OUT transactions
  getAllOutTransactions: async () => {
    try {
      const response = await adminApi.get(`${API_BASE_URL}/warehouses/audit-logs/out-transactions`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

};

export default adminService;
