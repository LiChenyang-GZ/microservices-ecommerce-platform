import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { orderAPI } from '../services/api';
import Button from '../components/Button';
import './MyOrdersPage.css';

const MyOrdersPage = () => {
  const navigate = useNavigate();
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // TODO: 从AuthContext获取userId，目前使用硬编码测试
  const userId = 1;

  useEffect(() => {
    fetchOrders();
  }, []);

  const fetchOrders = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await orderAPI.getUserOrdersWithPayment(userId);
      setOrders(data);
    } catch (err) {
      console.error('Failed to fetch orders:', err);
      setError('无法加载订单列表，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const handleOrderClick = (orderId) => {
    navigate(`/orders/${orderId}`);
  };

  const getStatusBadgeClass = (status) => {
    const statusMap = {
      'PENDING': 'status-pending',
      'SUCCESS': 'status-success',
      'PAID': 'status-success',
      'FAILED': 'status-failed',
      'REFUNDED': 'status-refunded',
      'PLACED': 'status-pending',
      'PROCESSING': 'status-pending',
      'CANCELLED': 'status-refunded'
    };
    return statusMap[status] || 'status-default';
  };

  const getStatusText = (status) => {
    const statusTextMap = {
      'PENDING': '待处理',
      'SUCCESS': '成功',
      'PAID': '已支付',
      'FAILED': '失败',
      'REFUNDED': '已退款',
      'PLACED': '已下单',
      'PROCESSING': '处理中',
      'CANCELLED': '已取消'
    };
    return statusTextMap[status] || status;
  };

  const formatDate = (dateString) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  if (loading) {
    return (
      <div className="orders-container">
        <div className="loading-spinner">
          <div className="spinner"></div>
          <p>加载订单中...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="orders-container">
      <div className="orders-header">
        <h1>我的订单</h1>
        <Button onClick={() => navigate('/')} variant="secondary">
          返回首页
        </Button>
      </div>

      {error && (
        <div className="error-message">
          <p>{error}</p>
          <Button onClick={fetchOrders}>重试</Button>
        </div>
      )}

      {!error && orders.length === 0 && (
        <div className="empty-state">
          <p>您还没有任何订单</p>
          <Button onClick={() => navigate('/')}>去购物</Button>
        </div>
      )}

      {!error && orders.length > 0 && (
        <div className="orders-list">
          {orders.map((orderWithPayment) => {
            const { order, payment } = orderWithPayment;
            return (
              <div
                key={order.id}
                className="order-card"
                onClick={() => handleOrderClick(order.id)}
              >
                <div className="order-card-header">
                  <div className="order-info">
                    <h3>订单 #{order.id}</h3>
                    <span className="order-date">{formatDate(order.createdAt)}</span>
                  </div>
                  <div className="order-badges">
                    <span className={`status-badge ${getStatusBadgeClass(order.status)}`}>
                      {getStatusText(order.status)}
                    </span>
                    {payment && (
                      <span className={`status-badge ${getStatusBadgeClass(payment.status)}`}>
                        {getStatusText(payment.status)}
                      </span>
                    )}
                  </div>
                </div>

                <div className="order-card-body">
                  <div className="order-items">
                    <p>商品数量: {order.orderItems?.length || 0} 件</p>
                  </div>
                  <div className="order-amount">
                    <span className="amount-label">总金额:</span>
                    <span className="amount-value">¥{order.totalAmount}</span>
                  </div>
                </div>

                {payment?.errorMessage && (
                  <div className="order-error">
                    <span className="error-icon">⚠️</span>
                    <span>{payment.errorMessage}</span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default MyOrdersPage;

