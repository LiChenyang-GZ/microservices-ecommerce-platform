import axios from 'axios';

// 创建axios实例
const api = axios.create({
  baseURL: 'http://localhost:8082/api', // 后端服务地址
  timeout: 10000, // 请求超时时间
  headers: {
    'Content-Type': 'application/json',
  },
});

// 请求拦截器
api.interceptors.request.use(
  (config) => {
    // 从 localStorage 获取 token 并添加到请求头
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    console.log('发送请求:', config);
    return config;
  },
  (error) => {
    console.error('请求错误:', error);
    return Promise.reject(error);
  }
);

// 格式化错误消息的工具函数
const formatErrorMessage = (error) => {
  if (!error.response || !error.response.data) {
    return error.message || '未知错误';
  }

  const { status, data } = error.response;
  
  // Bean Validation 错误 (400) - 提取字段错误
  if (status === 400 && data.errors) {
    const fieldErrors = data.errors
      .map(err => {
        const field = err.field || err.propertyPath || '';
        const msg = err.message || err.defaultMessage || '';
        return field ? `${field}: ${msg}` : msg;
      })
      .join('; ');
    return fieldErrors || data.message || '请求参数验证失败';
  }

  // 400 错误 - 尝试提取 message
  if (status === 400) {
    if (data.message) return data.message;
    if (data.error) return data.error;
    return '请求参数错误，请检查输入';
  }

  // 401 未授权
  if (status === 401) {
    return '登录已过期，请重新登录';
  }

  // 403 禁止访问
  if (status === 403) {
    return data.message || '没有权限执行此操作';
  }

  // 404 未找到
  if (status === 404) {
    return data.message || '资源未找到';
  }

  // 500 服务器错误
  if (status >= 500) {
    return data.message || '服务器错误，请稍后重试';
  }

  // 其他错误
  return data.message || data.error || `HTTP ${status}: 请求失败`;
};

// 响应拦截器
api.interceptors.response.use(
  (response) => {
    console.log('收到响应:', response);
    return response;
  },
  (error) => {
    // 格式化错误消息
    const errorMessage = formatErrorMessage(error);
    
    // 提取详细的错误信息用于控制台输出
    const errorDetails = {
      message: errorMessage,
      status: error.response?.status,
      data: error.response?.data,
      url: error.config?.url,
      method: error.config?.method?.toUpperCase()
    };

    console.error('【响应错误详情】', errorDetails);
    
    // 将格式化后的错误消息附加到 error 对象上，方便组件使用
    error.formattedMessage = errorMessage;
    error.errorDetails = errorDetails;

    // 统一处理错误
    if (error.response) {
      // 服务器返回了错误状态码
      const { status } = error.response;
      
      // 如果是 401 未授权，清除 token 并跳转到登录页
      if (status === 401) {
        localStorage.removeItem('token');
        localStorage.removeItem('userEmail');
        localStorage.removeItem('userId');
        localStorage.removeItem('isLoggedIn');
        // 可以在这里触发登出操作
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
      }
    } else if (error.request) {
      // 请求已发出但没有收到响应
      console.error('网络错误: 无法连接到服务器');
      error.formattedMessage = '网络错误：无法连接到服务器，请检查网络连接';
    } else {
      // 其他错误
      console.error('请求配置错误:', error.message);
      error.formattedMessage = error.message || '请求配置错误';
    }
    return Promise.reject(error);
  }
);

// 用户相关API
export const userAPI = {
  // 创建账户
  createAccount: async (accountData) => {
    try {
      const response = await api.post('/user', accountData);
      return response.data;
    } catch (error) {
      throw error;
    }
  },
  
  // 用户登录
  login: async (credentials) => {
    try {
      const response = await api.post('/user/login', credentials);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // 激活账户
  activateAccount: async (email) => {
    try {
      const response = await api.post('/user/activate', { email });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // 检查邮箱是否已验证
  checkEmailVerified: async (email) => {
    try {
      const response = await api.get(`/user/check-verified/${email}`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // 发送忘记密码邮件
  forgotPassword: async (email) => {
    try {
      const response = await api.post('/user/forgot-password', { email });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // 重置密码
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

// 邮件服务API - 使用单独的axios实例
const emailApi = axios.create({
  baseURL: 'http://localhost:8083/api', // 邮件服务地址
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

export const emailAPI = {
  // 发送验证码
  sendVerificationCode: async (email) => {
    try {
      const response = await emailApi.post('/email/send-verification', { email });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // 验证验证码
  verifyCode: async (email, code) => {
    try {
      const response = await emailApi.post('/email/verify-code', { email, code });
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // 检查邮箱是否已验证
  checkEmailVerified: async (email) => {
    try {
      const response = await emailApi.get(`/email/check-verified/${email}`);
      return response.data;
    } catch (error) {
      throw error;
    }
  }
};

// 创建一个新的axios实例，专门用于连接你的DeliveryCo服务
const deliveryApi = axios.create({
    baseURL: 'http://localhost:8081/api', // 你的DeliveryCo后端地址
    timeout: 10000,
    headers: { 'Content-Type': 'application/json' },
});

// 让deliveryApi也使用同样的请求拦截器来附加token
deliveryApi.interceptors.request.use(
  (config) => {
    // 从 localStorage 获取 token 并添加到请求头
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    console.log('Delivery API 请求:', config.url, 'Token:', token ? '已设置' : '未找到');
    return config;
  },
  (error) => {
    console.error('Delivery API 请求错误:', error);
    return Promise.reject(error);
  }
);

// 让deliveryApi也使用同样的响应拦截器来处理错误
deliveryApi.interceptors.response.use(
  (response) => {
    console.log('Delivery API 响应:', response.config.url, response.status);
    return response;
  },
  (error) => {
    console.error('Delivery API 响应错误:', error.config?.url, error.response?.status, error.response?.data);
    // 统一处理错误
    if (error.response) {
      const { status, data } = error.response;
      console.error(`Delivery API HTTP ${status}:`, data);
      
      // 如果是 401 未授权，清除 token 并跳转到登录页
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
      console.error('Delivery API 网络错误: 无法连接到服务器');
    } else {
      console.error('Delivery API 请求配置错误:', error.message);
    }
    return Promise.reject(error);
  }
);


// 导出你的DeliveryCo服务相关API
export const deliveryAPI = {
  /**
   * 获取当前登录用户的所有配送任务
   * @returns Promise
   */
  getMyDeliveries: () => {
    return deliveryApi.get(`/deliveries/me`);
  },
  
  /**
   * 根据邮箱获取所有配送任务（已废弃，请使用 getMyDeliveries）
   * @deprecated 请使用 getMyDeliveries()，不再需要传入 email 参数
   * @param {string} email
   * @returns Promise
   */
  getAllDeliveriesByEmail: (email) => {
    return deliveryApi.get(`/deliveries/all/${email}`);
  },

  /**
   * 根据ID获取单个配送任务
   * @param {string|number} id
   * @returns Promise
   */
  getDeliveryById: (id) => {
    return deliveryApi.get(`/deliveries/${id}`);
  },

  /**
   * 根据ID取消一个配送任务
   * @param {string|number} id
   * @returns Promise
   */
  cancelDeliveryById: (id) => {
    return deliveryApi.post(`/deliveries/${id}/cancel`);
  },
}
// 订单相关API
export const orderAPI = {
  // 获取用户订单（含支付信息）
  getUserOrdersWithPayment: async (userId) => {
    try {
      const response = await api.get(`/store/orders/user/${userId}/with-payment`);
      return response.data;
    } catch (error) {
      
      throw error;
    }
  },

  // 获取单个订单详情（含支付信息）
  getOrderWithPayment: async (orderId) => {
    try {
      const response = await api.get(`/store/orders/${orderId}/with-payment`);
      return response.data;
    } catch (error) {
      throw error;
    }
  },

  // 取消订单（统一入口：回退库存 + 如已扣款则退款 + 邮件）
  cancelOrder: async (orderId) => {
    try {
      const response = await api.post(`/store/orders/${orderId}/cancel`);
      return response.data;
    } catch (error) { throw error; }
  }
};

// 结算/下单（整合下单+支付）
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
      // 错误已在拦截器中格式化，直接抛出即可
      console.error('【下单失败】', error.errorDetails || error);
      throw error;
    }
  }
};

// 商品服务API（直接使用默认 api 实例，指向 http://localhost:8082/api）
export const productAPI = {
  // 获取全部商品
  getAll: async () => {
    try {
      const res = await api.get('/products');
      return res.data?.products || [];
    } catch (e) { throw e; }
  },

  // 仅获取有库存的商品
  getAvailable: async () => {
    try {
      const res = await api.get('/products/available');
      return res.data?.products || [];
    } catch (e) { throw e; }
  },

  // 根据ID获取
  getById: async (id) => {
    try {
      const res = await api.get(`/products/${id}`);
      return res.data?.product || null;
    } catch (e) { throw e; }
  },

  // 按名称搜索
  searchByName: async (name) => {
    try {
      const res = await api.get('/products/search', { params: { name } });
      return res.data?.products || [];
    } catch (e) { throw e; }
  },
};

// 支付相关API
export const paymentAPI = {
  // 根据订单ID查询支付状态
  getPaymentByOrderId: async (orderId) => {
    try {
      const response = await api.get(`/payments/order/${orderId}`);
      return response.data;
    } catch (error) {
      // 错误已在拦截器中格式化，直接抛出即可
      console.error('【查询支付状态失败】', error.errorDetails || error);
      throw error;
    }
  },

  // 申请退款
  refundPayment: async (orderId, reason = 'Customer requested refund') => {
    try {
      const response = await api.post(`/payments/${orderId}/refund`, { reason });
      return response.data;
    } catch (error) {
      // 错误已在拦截器中格式化，直接抛出即可
      console.error('【退款失败】', error.errorDetails || error);
      throw error;
    }
  }
  
};

export default api;
