import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { orderAPI } from '../services/api';
import Button from '../components/Button';
import './OrderDetailPage.css';

const OrderDetailPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const [orderData, setOrderData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetchOrderDetail();
  }, [id]);

  const fetchOrderDetail = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await orderAPI.getOrderWithPayment(id);
      setOrderData(data);
    } catch (err) {
      console.error('Failed to fetch order detail:', err);
      setError('Unable to load order details, please try again later');
    } finally {
      setLoading(false);
    }
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
    return date.toLocaleString('en-US', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    });
  };

  if (loading) {
    return (
      <div className="order-detail-container">
        <div className="loading-spinner">
          <div className="spinner"></div>
          <p>Loading order details...</p>
        </div>
      </div>
    );
  }

  if (error || !orderData) {
    return (
      <div className="order-detail-container">
        <div className="error-message">
          <p>{error || 'Order not found'}</p>
          <div className="button-group">
            <Button onClick={() => navigate('/orders')}>Back to Order List</Button>
            {error && <Button onClick={fetchOrderDetail} variant="secondary">Retry</Button>}
          </div>
        </div>
      </div>
    );
  }

  const { order, payment } = orderData;

  return (
    <div className="order-detail-container">
      <div className="detail-header">
        <div className="header-left">
          <Button onClick={() => navigate('/orders')} variant="secondary">
            ← Back to Order List
          </Button>
          <h1>Order Details #{order.id}</h1>
        </div>
      </div>

      {/* Order status card */}
      <div className="detail-card">
        <h2>Order Status</h2>
        <div className="status-section">
          <div className="status-item">
            <span className="status-label">Order Status:</span>
            <span className={`status-badge ${getStatusBadgeClass(order.status)}`}>
              {getStatusText(order.status)}
            </span>
          </div>
          {payment && (
            <div className="status-item">
              <span className="status-label">Payment Status:</span>
              <span className={`status-badge ${getStatusBadgeClass(payment.status)}`}>
                {getStatusText(payment.status)}
              </span>
            </div>
          )}
        </div>
        <div className="info-row">
          <span className="info-label">Created At:</span>
          <span className="info-value">{formatDate(order.createdAt)}</span>
        </div>
        {order.updatedAt && (
          <div className="info-row">
            <span className="info-label">Updated At:</span>
            <span className="info-value">{formatDate(order.updatedAt)}</span>
          </div>
        )}
      </div>

      {/* Payment information card */}
      {payment && (
        <div className="detail-card">
          <h2>Payment Information</h2>
          <div className="info-row">
            <span className="info-label">Payment ID:</span>
            <span className="info-value">#{payment.id}</span>
          </div>
          {payment.bankTxnId && (
            <div className="info-row">
              <span className="info-label">Bank Transaction ID:</span>
              <span className="info-value mono">{payment.bankTxnId}</span>
            </div>
          )}
          <div className="info-row">
            <span className="info-label">Payment Amount:</span>
            <span className="info-value amount">¥{payment.amount}</span>
          </div>
          {payment.errorMessage && (
            <div className="error-box">
              <div className="error-title">
                <span className="error-icon">⚠️</span>
                <span>Payment Failure Reason</span>
              </div>
              <p className="error-text">{payment.errorMessage}</p>
            </div>
          )}
        </div>
      )}

      {/* Order product list */}
      <div className="detail-card">
        <h2>Product List</h2>
        {order.orderItems && order.orderItems.length > 0 ? (
          <div className="items-list">
            {order.orderItems.map((item, index) => (
              <div key={index} className="item-row">
                  <div className="item-info">
                    <span className="item-name">{item.productName || `Product #${item.productId}`}</span>
                    <span className="item-price">Unit Price: ¥{item.unitPrice}</span>
                  </div>
                  <div className="item-right">
                    <span className="item-qty">x {item.qty}</span>
                    <span className="item-total">¥{item.totalPrice}</span>
                  </div>
                </div>
              ))}
            <div className="total-row">
              <span className="total-label">Total:</span>
              <span className="total-amount">¥{order.totalAmount}</span>
            </div>
          </div>
        ) : (
          <p className="no-items">No product information</p>
        )}
      </div>

      {/* Action buttons */}
      {payment && payment.status === 'FAILED' && (
        <div className="action-section">
          <div className="action-hint">
            <p>Payment failed, you can retry payment or cancel the order</p>
          </div>
          <div className="button-group">
            <Button onClick={() => alert('Retry payment feature coming soon')} variant="primary">
              Retry Payment
            </Button>
          </div>
        </div>
      )}
    </div>
  );
};

export default OrderDetailPage;

