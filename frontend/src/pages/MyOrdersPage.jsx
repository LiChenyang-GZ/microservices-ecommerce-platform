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

  // TODO: Get userId from AuthContext, currently using hardcoded value for testing
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
      setError('Unable to load order list, please try again later');
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
      'PENDING': 'Pending',
      'SUCCESS': 'Success',
      'PAID': 'Paid',
      'FAILED': 'Failed',
      'REFUNDED': 'Refunded',
      'PLACED': 'Placed',
      'PROCESSING': 'Processing',
      'CANCELLED': 'Cancelled'
    };
    return statusTextMap[status] || status;
  };

  const formatDate = (dateString) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', {
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
          <p>Loading orders...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="orders-container">
      <div className="orders-header">
        <h1>My Orders</h1>
        <Button onClick={() => navigate('/')} variant="secondary">
          Back to Home
        </Button>
      </div>

      {error && (
        <div className="error-message">
          <p>{error}</p>
          <Button onClick={fetchOrders}>Retry</Button>
        </div>
      )}

      {!error && orders.length === 0 && (
        <div className="empty-state">
          <p>You don't have any orders yet</p>
          <Button onClick={() => navigate('/')}>Go Shopping</Button>
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
                    <h3>Order #{order.id}</h3>
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
                    <p>Product Count: {order.orderItems?.length || 0} items</p>
                  </div>
                  <div className="order-amount">
                    <span className="amount-label">Total Amount:</span>
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

