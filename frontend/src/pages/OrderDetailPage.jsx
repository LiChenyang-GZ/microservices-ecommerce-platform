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
      setError('无法加载订单详情，请稍后重试');
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
    return date.toLocaleString('zh-CN', {
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
          <p>加载订单详情中...</p>
        </div>
      </div>
    );
  }

  if (error || !orderData) {
    return (
      <div className="order-detail-container">
        <div className="error-message">
          <p>{error || '订单不存在'}</p>
          <div className="button-group">
            <Button onClick={() => navigate('/orders')}>返回订单列表</Button>
            {error && <Button onClick={fetchOrderDetail} variant="secondary">重试</Button>}
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
            ← 返回订单列表
          </Button>
          <h1>订单详情 #{order.id}</h1>
        </div>
      </div>

      {/* 订单状态卡片 */}
      <div className="detail-card">
        <h2>订单状态</h2>
        <div className="status-section">
          <div className="status-item">
            <span className="status-label">订单状态:</span>
            <span className={`status-badge ${getStatusBadgeClass(order.status)}`}>
              {getStatusText(order.status)}
            </span>
          </div>
          {payment && (
            <div className="status-item">
              <span className="status-label">支付状态:</span>
              <span className={`status-badge ${getStatusBadgeClass(payment.status)}`}>
                {getStatusText(payment.status)}
              </span>
            </div>
          )}
        </div>
        <div className="info-row">
          <span className="info-label">创建时间:</span>
          <span className="info-value">{formatDate(order.createdAt)}</span>
        </div>
        {order.updatedAt && (
          <div className="info-row">
            <span className="info-label">更新时间:</span>
            <span className="info-value">{formatDate(order.updatedAt)}</span>
          </div>
        )}
      </div>

      {/* 支付信息卡片 */}
      {payment && (
        <div className="detail-card">
          <h2>支付信息</h2>
          <div className="info-row">
            <span className="info-label">支付ID:</span>
            <span className="info-value">#{payment.id}</span>
          </div>
          {payment.bankTxnId && (
            <div className="info-row">
              <span className="info-label">银行交易ID:</span>
              <span className="info-value mono">{payment.bankTxnId}</span>
            </div>
          )}
          <div className="info-row">
            <span className="info-label">支付金额:</span>
            <span className="info-value amount">¥{payment.amount}</span>
          </div>
          {payment.errorMessage && (
            <div className="error-box">
              <div className="error-title">
                <span className="error-icon">⚠️</span>
                <span>支付失败原因</span>
              </div>
              <p className="error-text">{payment.errorMessage}</p>
            </div>
          )}
        </div>
      )}

      {/* 订单商品列表 */}
      <div className="detail-card">
        <h2>商品清单</h2>
        {order.orderItems && order.orderItems.length > 0 ? (
          <div className="items-list">
            {order.orderItems.map((item, index) => (
              <div key={index} className="item-row">
                <div className="item-info">
                  <span className="item-name">{item.productName || `商品 #${item.productId}`}</span>
                  <span className="item-price">单价: ¥{item.unitPrice}</span>
                </div>
                <div className="item-right">
                  <span className="item-qty">x {item.qty}</span>
                  <span className="item-total">¥{item.totalPrice}</span>
                </div>
              </div>
            ))}
            <div className="total-row">
              <span className="total-label">总计:</span>
              <span className="total-amount">¥{order.totalAmount}</span>
            </div>
          </div>
        ) : (
          <p className="no-items">暂无商品信息</p>
        )}
      </div>

      {/* 操作按钮 */}
      {payment && payment.status === 'FAILED' && (
        <div className="action-section">
          <div className="action-hint">
            <p>支付失败，您可以重新尝试支付或取消订单</p>
          </div>
          <div className="button-group">
            <Button onClick={() => alert('重试支付功能即将推出')} variant="primary">
              重试支付
            </Button>
          </div>
        </div>
      )}
    </div>
  );
};

export default OrderDetailPage;

