import axios from 'axios';

const API_BASE_URL = 'http://localhost:8082/api';

const adminService = {
  // Order Module - Get all orders
  getAllOrders: async () => {
    try {
      const response = await axios.get(`${API_BASE_URL}/orders`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Product Module - Get all products
  getAllProducts: async () => {
    try {
      const response = await axios.get(`${API_BASE_URL}/products`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Warehouse Module - Get all warehouses
  getAllWarehouses: async () => {
    try {
      const response = await axios.get(`${API_BASE_URL}/warehouses`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Inventory Audit Report - Get all OUT transactions
  getAllOutTransactions: async () => {
    try {
      const response = await axios.get(`${API_BASE_URL}/warehouses/audit-logs/out-transactions`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Get order inventory records for a specific order
  getOrderInventoryRecords: async (orderId) => {
    try {
      const response = await axios.get(`${API_BASE_URL}/orders/${orderId}/inventory-records`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Get warehouse inventory records for a specific warehouse
  getWarehouseInventoryRecords: async (warehouseId) => {
    try {
      const response = await axios.get(`${API_BASE_URL}/warehouses/${warehouseId}/inventory-records`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Get product inventory records for a specific product
  getProductInventoryRecords: async (productId) => {
    try {
      const response = await axios.get(`${API_BASE_URL}/products/${productId}/inventory-records`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },
};

export default adminService;
