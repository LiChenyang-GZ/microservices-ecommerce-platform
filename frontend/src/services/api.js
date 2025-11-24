import axios from 'axios';

// Create axios instance
const api = axios.create({
  baseURL: 'http://localhost:8082/api', // Backend service address
  timeout: 10000, // Request timeout
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor
api.interceptors.request.use(
  (config) => {
    // Get token from localStorage and add to request header
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    console.log('Sending request:', config);
    return config;
  },
  (error) => {
    console.error('Request error:', error);
    return Promise.reject(error);
  }
);

// Utility function to format error messages
const formatErrorMessage = (error) => {
  if (!error.response || !error.response.data) {
    return error.message || 'Unknown error';
  }

  const { status, data } = error.response;
  
  // Bean Validation error (400) - extract field errors
  if (status === 400 && data.errors) {
    const fieldErrors = data.errors
      .map(err => {
        const field = err.field || err.propertyPath || '';
        const msg = err.message || err.defaultMessage || '';
        return field ? `${field}: ${msg}` : msg;
      })
      .join('; ');
    return fieldErrors || data.message || 'Request parameter validation failed';
  }

  // 400 error - try to extract message
  if (status === 400) {
    if (data.message) return data.message;
    if (data.error) return data.error;
    return 'Request parameter error, please check input';
  }

  // 401 Unauthorized
  if (status === 401) {
    return 'Login expired, please login again';
  }

  // 403 Forbidden
  if (status === 403) {
    return data.message || 'No permission to perform this operation';
  }

  // 404 Not Found
  if (status === 404) {
    return data.message || 'Resource not found';
  }

  // 500 Server Error
  if (status >= 500) {
    return data.message || 'Server error, please try again later';
  }

  // Other errors
  return data.message || data.error || `HTTP ${status}: Request failed`;
};

// Response interceptor
api.interceptors.response.use(
  (response) => {
    console.log('Received response:', response);
    return response;
  },
  (error) => {
    // Format error message
    const errorMessage = formatErrorMessage(error);
    
    // Extract detailed error information for console output
    const errorDetails = {
      message: errorMessage,
      status: error.response?.status,
      data: error.response?.data,
      url: error.config?.url,
      method: error.config?.method?.toUpperCase()
    };

    console.error('[Response Error Details]', errorDetails);
    
    // Attach formatted error message to error object for easy component use
    error.formattedMessage = errorMessage;
    error.errorDetails = errorDetails;

    // Unified error handling
    if (error.response) {
      // Server returned error status code
      const { status } = error.response;
      
      // If 401 Unauthorized, clear token and redirect to login page
      if (status === 401) {
        localStorage.removeItem('token');
        localStorage.removeItem('userEmail');
        localStorage.removeItem('userId');
        localStorage.removeItem('isLoggedIn');
        // Can trigger logout operation here
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
      }
    } else if (error.request) {
      // Request was sent but no response received
      console.error('Network error: Unable to connect to server');
      error.formattedMessage = 'Network error: Unable to connect to server, please check network connection';
    } else {
      // Other errors
      console.error('Request configuration error:', error.message);
      error.formattedMessage = error.message || 'Request configuration error';
    }
    return Promise.reject(error);
  }
);

// User related API
export const userAPI = {
  // Create account
  createAccount: async (accountData) => {
    try {
      const response = await api.post('/user', accountData);
      return response.data;
    } catch (error) {
      throw error;
    }
  },
  
  // User login
  login: async (credentials) => {
    try {
      const response = await api.post('/user/login', credentials);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Activate account
  activateAccount: async (email) => {
    try {
      const response = await api.post('/user/activate', { email });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Check if email is verified
  checkEmailVerified: async (email) => {
    try {
      const response = await api.get(`/user/check-verified/${email}`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Send forgot password email
  forgotPassword: async (email) => {
    try {
      const response = await api.post('/user/forgot-password', { email });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Reset password
  resetPassword: async (email, code, newPassword) => {
    try {
      const response = await api.post('/user/reset-password', { 
        email, 
        code, 
        newPassword 
      });
      return response.data;
    } catch (error) {
      throw error;
    }
  }
};

// Email service API - using separate axios instance
const emailApi = axios.create({
  baseURL: 'http://localhost:8083/api', // Email service address
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

export const emailAPI = {
  // Send verification code
  sendVerificationCode: async (email) => {
    try {
      const response = await emailApi.post('/email/send-verification', { email });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Verify code
  verifyCode: async (email, code) => {
    try {
      const response = await emailApi.post('/email/verify-code', { email, code });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Check if email is verified
  checkEmailVerified: async (email) => {
    try {
      const response = await emailApi.get(`/email/check-verified/${email}`);
      return response.data;
    } catch (error) {
      throw error;
    }
  }
};

// Create a new axios instance specifically for connecting to your DeliveryCo service
const deliveryApi = axios.create({
    baseURL: 'http://localhost:8081/api', // Your DeliveryCo backend address
    timeout: 10000,
    headers: { 'Content-Type': 'application/json' },
});

// Make deliveryApi also use the same request interceptor to attach token
deliveryApi.interceptors.request.use(
  (config) => {
    // Get token from localStorage and add to request header
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    console.log('Delivery API request:', config.url, 'Token:', token ? 'Set' : 'Not found');
    return config;
  },
  (error) => {
    console.error('Delivery API request error:', error);
    return Promise.reject(error);
  }
);

// Make deliveryApi also use the same response interceptor to handle errors
deliveryApi.interceptors.response.use(
  (response) => {
    console.log('Delivery API response:', response.config.url, response.status);
    return response;
  },
  (error) => {
    console.error('Delivery API response error:', error.config?.url, error.response?.status, error.response?.data);
    // Unified error handling
    if (error.response) {
      const { status, data } = error.response;
      console.error(`Delivery API HTTP ${status}:`, data);
      
      // If 401 Unauthorized, clear token and redirect to login page
      if (status === 401) {
        localStorage.removeItem('token');
        localStorage.removeItem('userEmail');
        localStorage.removeItem('userId');
        localStorage.removeItem('isLoggedIn');
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
      }
    } else if (error.request) {
      console.error('Delivery API network error: Unable to connect to server');
    } else {
      console.error('Delivery API request configuration error:', error.message);
    }
    return Promise.reject(error);
  }
);


// Export your DeliveryCo service related API
export const deliveryAPI = {
  /**
   * Get all delivery tasks for the currently logged in user
   * @returns Promise
   */
  getMyDeliveries: () => {
    return deliveryApi.get(`/deliveries/me`);
  },
  
  /**
   * Get all delivery tasks by email (deprecated, please use getMyDeliveries)
   * @deprecated Please use getMyDeliveries(), email parameter is no longer needed
   * @param {string} email
   * @returns Promise
   */
  getAllDeliveriesByEmail: (email) => {
    return deliveryApi.get(`/deliveries/all/${email}`);
  },

  /**
   * Get a single delivery task by ID
   * @param {string|number} id
   * @returns Promise
   */
  getDeliveryById: (id) => {
    return deliveryApi.get(`/deliveries/${id}`);
  },

  /**
   * Cancel a delivery task by ID
   * @param {string|number} id
   * @returns Promise
   */
  cancelDeliveryById: (id) => {
    return deliveryApi.post(`/deliveries/${id}/cancel`);
  },
}
// Order related API
export const orderAPI = {
  // Get user orders (including payment information)
  getUserOrdersWithPayment: async (userId) => {
    try {
      const response = await api.get(`/store/orders/user/${userId}/with-payment`);
      return response.data;
    } catch (error) {
      
      throw error;
    }
  },

  // Get single order details (including payment information)
  getOrderWithPayment: async (orderId) => {
    try {
      const response = await api.get(`/store/orders/${orderId}/with-payment`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Cancel order (unified entry: rollback inventory + refund if payment deducted + email)
  cancelOrder: async (orderId) => {
    try {
      const response = await api.post(`/store/orders/${orderId}/cancel`);
      return response.data;
    } catch (error) { throw error; }
  },

  // Retry/Complete payment for an existing unpaid order
  retryPayment: async (orderId) => {
    try {
      const response = await api.post(`/store/orders/${orderId}/retry-payment`);
      return response.data;
    } catch (error) { throw error; }
  }
};

// Checkout/Order creation (integrated order + payment)
export const checkoutAPI = {
  createOrderWithPayment: async ({ userId, items }) => {
    // items: [{ productId, qty }]
    try {
      const response = await api.post('/store/orders/create-with-payment', {
        userId,
        orderItems: items.map(item => ({
          productId: item.productId,
          quantity: item.qty || item.quantity || 1
        }))
      });
      return response.data; // OrderResponse
    } catch (error) {
      // Error already formatted in interceptor, just throw it
      console.error('[Order Creation Failed]', error.errorDetails || error);
      throw error;
    }
  }
};

// Product service API (using default api instance, pointing to http://localhost:8082/api)
export const productAPI = {
  // Get all products
  getAll: async () => {
    try {
      const res = await api.get('/products');
      return res.data?.products || [];
    } catch (e) { throw e; }
  },

  // Get only products with stock
  getAvailable: async () => {
    try {
      const res = await api.get('/products/available');
      return res.data?.products || [];
    } catch (e) { throw e; }
  },

  // Get by ID
  getById: async (id) => {
    try {
      const res = await api.get(`/products/${id}`);
      return res.data?.product || null;
    } catch (e) { throw e; }
  },

  // Search by name
  searchByName: async (name) => {
    try {
      const res = await api.get('/products/search', { params: { name } });
      return res.data?.products || [];
    } catch (e) { throw e; }
  },
};

// Payment related API
export const paymentAPI = {
  // Query payment status by order ID
  getPaymentByOrderId: async (orderId) => {
    try {
      const response = await api.get(`/payments/order/${orderId}`);
      return response.data;
    } catch (error) {
      // Error already formatted in interceptor, just throw it
      console.error('[Query Payment Status Failed]', error.errorDetails || error);
      throw error;
    }
  },

  // Apply for refund
  refundPayment: async (orderId, reason = 'Customer requested refund') => {
    try {
      const response = await api.post(`/payments/${orderId}/refund`, { reason });
      return response.data;
    } catch (error) {
      // Error already formatted in interceptor, just throw it
      console.error('[Refund Failed]', error.errorDetails || error);
      throw error;
    }
  }

};

// Bank service API - using separate axios instance
const bankApi = axios.create({
  baseURL: 'http://localhost:8084/api', // Bank service address
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Make bankApi use the same request interceptor to attach token
bankApi.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    console.log('Bank API request:', config.url);
    return config;
  },
  (error) => {
    console.error('Bank API request error:', error);
    return Promise.reject(error);
  }
);

// Make bankApi use response interceptor to handle errors
bankApi.interceptors.response.use(
  (response) => {
    console.log('Bank API response:', response.config.url, response.status);
    return response;
  },
  (error) => {
    console.error('Bank API response error:', error.config?.url, error.response?.status);
    error.formattedMessage = formatErrorMessage(error);

    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('userEmail');
      localStorage.removeItem('userId');
      localStorage.removeItem('isLoggedIn');
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }

    return Promise.reject(error);
  }
);

export const bankAPI = {
  // Get or create my bank account (uses JWT token to identify user)
  getOrCreateAccount: async () => {
    try {
      const response = await bankApi.get('/bank/account/me');
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Get account balance
  getBalance: async (accountNumber) => {
    try {
      const response = await bankApi.get(`/bank/account/${accountNumber}/balance`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Add money to account (simulated by transferring from SYSTEM account)
  addMoney: async (toAccount, amount) => {
    try {
      const response = await bankApi.post('/bank/transfer', {
        fromAccount: 'SYSTEM',
        toAccount: toAccount,
        amount: amount,
        transactionRef: `DEPOSIT-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
      });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // Transfer money between accounts
  transfer: async (fromAccount, toAccount, amount) => {
    try {
      const response = await bankApi.post('/bank/transfer', {
        fromAccount,
        toAccount,
        amount,
        transactionRef: `TRANSFER-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
      });
      return response.data;
    } catch (error) {
      throw error;
    }
  }
};

export default api;
