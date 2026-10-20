import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import BackButton from '../components/BackButton';
import { orderAPI, paymentAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import './OrderDetailPage.css';

// Pay Now only queues the charge; the bank call happens in the background (OutboxProcessor).
// While the payment is PENDING the page re-reads the order every few seconds until it settles.
export const PAYMENT_POLL_INTERVAL_MS = 3000;

function OrderDetailPage() {
    const [order, setOrder] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const [isCancelling, setIsCancelling] = useState(false);
    const [isRefunding, setIsRefunding] = useState(false);
    const [isPaying, setIsPaying] = useState(false);
    const [error, setError] = useState('');
    const { id } = useParams();
    const navigate = useNavigate();
    const { isLoggedIn, userId, isLoading: authLoading } = useAuth();

    // silent = background refresh while waiting for the payment: keep the page on screen, no loading spinner
    const fetchOrderDetails = useCallback(async ({ silent = false } = {}) => {
        try {
            if (!silent) setIsLoading(true);
            const response = await orderAPI.getOrderWithPayment(id);

            // Transform the nested structure to flat structure
            const transformedOrder = {
                orderId: response.order?.id,
                orderDate: response.order?.createdAt,
                orderStatus: response.order?.status,
                deliveryId: response.order?.deliveryId,
                totalAmount: response.order?.totalAmount,
                items: response.order ? [{
                    productId: response.order.productId,
                    productName: response.order.productName,
                    quantity: response.order.quantity,
                    unitPrice: response.order.unitPrice
                }] : [],
                payment: response.payment
            };

            setOrder(transformedOrder);
            setError('');
        } catch (err) {
            console.error('Failed to fetch order details:', err);
            if (err.response?.status === 401) {
                setError('Please login first');
                navigate('/login');
            } else if (err.response?.status === 404) {
                setError('Order not found or you do not have permission to access this order');
            } else {
                setError(err.formattedMessage || 'Unable to load order details, please try again later.');
            }
        } finally {
            if (!silent) setIsLoading(false);
        }
    }, [id, navigate]);

    useEffect(() => {
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }
        if (isLoggedIn) {
            fetchOrderDetails();
        }
    }, [isLoggedIn, authLoading, id, navigate, fetchOrderDetails]);

    // Poll while the charge is still in progress (just placed, or right after Pay Now).
    // Stops by itself once the status is SUCCESS / FAILED / PAY_UNKNOWN, or when the page closes.
    const currentPaymentStatus = order?.payment?.status?.toUpperCase() || '';
    useEffect(() => {
        if (currentPaymentStatus !== 'PENDING') return undefined;
        const timer = setInterval(() => fetchOrderDetails({ silent: true }), PAYMENT_POLL_INTERVAL_MS);
        return () => clearInterval(timer);
    }, [currentPaymentStatus, fetchOrderDetails]);

    const handleCancelOrder = async () => {
        if (!window.confirm('Are you sure you want to cancel this order? This will process a refund if payment was made.')) {
            return;
        }

        try {
            setIsCancelling(true);
            await orderAPI.cancelOrder(id);
            alert('Order successfully cancelled! Any payment will be refunded shortly.');
            await fetchOrderDetails(); // Refresh order details
        } catch (err) {
            console.error('Cancellation failed:', err);
            let errorMessage = 'Cancellation failed';
            if (err.response?.status === 401) {
                errorMessage = 'Please login first';
                navigate('/login');
            } else if (err.response?.status === 404 || err.response?.status === 403) {
                errorMessage = 'Order not found or you do not have permission to cancel this order';
            } else {
                errorMessage = err.formattedMessage || 'This order may no longer be cancellable';
            }
            alert(errorMessage);
        } finally {
            setIsCancelling(false);
        }
    };

    const handleRefund = async () => {
        if (!window.confirm('Are you sure you want to request a refund for this order?')) {
            return;
        }

        try {
            setIsRefunding(true);
            await paymentAPI.refundPayment(id, 'Customer requested refund');
            alert('Refund requested. It will be processed shortly.');
            await fetchOrderDetails(); // Refresh order details
        } catch (err) {
            console.error('Refund failed:', err);
            const errorMessage = err.formattedMessage || 'Refund request failed, please try again later.';
            alert(errorMessage);
        } finally {
            setIsRefunding(false);
        }
    };

    const handlePayNow = async () => {
        if (!window.confirm('Proceed to pay for this order? Make sure you have sufficient balance in your account.')) {
            return;
        }

        try {
            setIsPaying(true);
            // Queues a new payment attempt. The result arrives later: the polling above picks it up.
            await orderAPI.retryPayment(order.orderId);
            await fetchOrderDetails({ silent: true });
        } catch (err) {
            console.error('Pay Now failed:', err);
            let errorMessage = 'Could not start the payment. ';
            if (err.formattedMessage) {
                errorMessage += err.formattedMessage;
            } else if (err.message) {
                errorMessage += err.message;
            }
            alert(errorMessage);
        } finally {
            setIsPaying(false);
        }
    };

    const formatDate = (dateString) => {
        if (!dateString) return 'N/A';
        const date = new Date(dateString);
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
    };

    const formatPrice = (price) => {
        return `$${parseFloat(price || 0).toFixed(2)}`;
    };

    const getStatusClass = (status) => {
        const statusLower = status?.toLowerCase();
        if (statusLower === 'completed' || statusLower === 'delivered' || statusLower === 'success') return 'status-completed';
        if (statusLower === 'cancelled' || statusLower === 'canceled' || statusLower === 'refunded') return 'status-cancelled';
        if (['pending', 'pay_unknown', 'refund_pending', 'refund_unknown'].includes(statusLower)) return 'status-pending';
        if (statusLower === 'processing' || statusLower === 'confirmed') return 'status-processing';
        return 'status-default';
    };

    // Determine if order can be cancelled (not allowed after pickup/shipping)
    const canCancel = order && order.orderStatus &&
        !['CANCELLED', 'CANCELED', 'COMPLETED', 'DELIVERED', 'SHIPPED', 'IN_TRANSIT', 'DELIVERING'].includes(order.orderStatus.toUpperCase());

    // Determine if refund is available - hide when in delivery or already cancelled
    // Note: Cancel button already handles refunds, so refund button is only for special cases
    const normalizedOrderStatus = order?.orderStatus?.toUpperCase() || '';
    const isCancelledStatus = normalizedOrderStatus.includes('CANCELLED');

    const canRefund = order && order.payment &&
        order.payment.status &&
        order.payment.status.toUpperCase() === 'SUCCESS' &&
        order.orderStatus &&
        !['CANCELLED', 'CANCELED', 'REFUNDED', 'SHIPPED', 'IN_TRANSIT', 'DELIVERING', 'DELIVERED'].includes(normalizedOrderStatus);

    const paymentStatusNormalized = order?.payment?.status?.toUpperCase() || '';

    // Pay Now only makes sense after a definite failure (rule 8). While the charge is PENDING or its result is
    // unknown, another attempt could charge twice, so the button is hidden and a status message is shown instead.
    const awaitingPayment = order && !isCancelledStatus && normalizedOrderStatus === 'PENDING_PAYMENT';
    const needsPayment = awaitingPayment && (!order.payment || paymentStatusNormalized === 'FAILED');
    const paymentInProgress = awaitingPayment && paymentStatusNormalized === 'PENDING';
    const paymentUnknown = awaitingPayment && paymentStatusNormalized === 'PAY_UNKNOWN';

    if (isLoading) {
        return <div className="loading-container">Loading order details...</div>;
    }

    if (error) {
        return (
            <div className="order-detail-container">
                <BackButton fallback="/orders" />
                <div className="error-message">{error}</div>
            </div>
        );
    }

    if (!order) {
        return (
            <div className="order-detail-container">
                <BackButton fallback="/orders" />
                <div className="error-message">Order not found.</div>
            </div>
        );
    }

    return (
        <div className="order-detail-container">
            <BackButton fallback="/orders" />
            <h1 className="page-title">Order Details</h1>

            <div className="detail-section">
                <h2 className="section-title">Order Information</h2>
                <div className="info-grid">
                    <div className="info-item">
                        <span className="label">Order ID:</span>
                        <span className="value">#{order.orderId}</span>
                    </div>
                    <div className="info-item">
                        <span className="label">Order Date:</span>
                        <span className="value">{formatDate(order.orderDate)}</span>
                    </div>
                    <div className="info-item">
                        <span className="label">Status:</span>
                        <span className={`value order-status ${getStatusClass(order.orderStatus)}`}>
                            {order.orderStatus}
                        </span>
                    </div>
                    <div className="info-item">
                        <span className="label">Total Amount:</span>
                        <span className="value price">{formatPrice(order.totalAmount)}</span>
                    </div>
                </div>
            </div>

            <div className="detail-section">
                <h2 className="section-title">Order Items</h2>
                {order.items && order.items.length > 0 ? (
                    <div className="items-table">
                        <table>
                            <thead>
                                <tr>
                                    <th>Product</th>
                                    <th>Quantity</th>
                                    <th>Unit Price</th>
                                    <th>Subtotal</th>
                                </tr>
                            </thead>
                            <tbody>
                                {order.items.map((item, index) => (
                                    <tr key={index}>
                                            <td>{item.productId ? (
                                                <Link to={`/products/${item.productId}`} className="product-link">
                                                    {item.productName || `Product #${item.productId}`}
                                                </Link>
                                            ) : (
                                                item.productName || `Product ID: ${item.productId}`
                                            )}</td>
                                        <td>{item.quantity}</td>
                                        <td>{formatPrice(item.unitPrice)}</td>
                                        <td>{formatPrice(item.quantity * item.unitPrice)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                ) : (
                    <p className="no-data">No item details available</p>
                )}
            </div>

            {order.payment && (
                <div className="detail-section">
                    <h2 className="section-title">Payment Information</h2>
                    <div className="info-grid">
                        <div className="info-item">
                            <span className="label">Payment ID:</span>
                            <span className="value">{order.payment.paymentId}</span>
                        </div>
                        <div className="info-item">
                            <span className="label">Payment Status:</span>
                            <span className={`value payment-status ${getStatusClass(order.payment.status)}`}>
                                {order.payment.status}
                            </span>
                        </div>
                        <div className="info-item">
                            <span className="label">Payment Amount:</span>
                            <span className="value price">{formatPrice(order.payment.amount)}</span>
                        </div>
                        <div className="info-item">
                            <span className="label">Payment Date:</span>
                            <span className="value">{formatDate(order.payment.paymentDate)}</span>
                        </div>
                    </div>
                </div>
            )}

            {order.deliveryId && (
                <div className="detail-section">
                    <h2 className="section-title">Delivery Information</h2>
                    <div className="info-grid">
                        <div className="info-item">
                            <span className="label">Delivery Task</span>
                            <Link to={`/delivery/${order.deliveryId}`} className="value delivery-link">
                                View delivery #{order.deliveryId}
                            </Link>
                        </div>
                    </div>
                </div>
            )}

            {paymentInProgress && (
                <div className="payment-alert" role="status">
                    <p className="alert-message">⏳ Payment processing… This page updates automatically.</p>
                </div>
            )}

            {paymentUnknown && (
                <div className="payment-alert" role="status">
                    <p className="alert-message">
                        🏦 The bank is still processing this payment. Please check back later — you will not be charged twice.
                    </p>
                </div>
            )}

            {needsPayment && (
                <div className="payment-alert">
                    <p className="alert-message">
                        ⚠️ This order is awaiting payment. Please complete the payment to confirm your order.
                    </p>
                    {paymentStatusNormalized === 'FAILED' && (
                        <p className="alert-hint">
                            {order.payment?.errorMessage ? `Last attempt failed: ${order.payment.errorMessage}. ` : ''}
                            Make sure you have sufficient balance in your account.
                        </p>
                    )}
                </div>
            )}

            <div className="action-buttons">
                {needsPayment && (
                    <button
                        onClick={handlePayNow}
                        className="btn btn-primary btn-pay"
                        disabled={isPaying}
                    >
                        {isPaying ? 'Starting payment...' : '💳 Pay Now'}
                    </button>
                )}
                {canCancel && (
                    <button
                        onClick={handleCancelOrder}
                        className="btn btn-danger"
                        disabled={isCancelling}
                    >
                        {isCancelling ? 'Cancelling...' : 'Cancel Order'}
                    </button>
                )}
                {canRefund && (
                    <button
                        onClick={handleRefund}
                        className="btn btn-warning"
                        disabled={isRefunding}
                    >
                        {isRefunding ? 'Processing Refund...' : 'Request Refund'}
                    </button>
                )}
            </div>
        </div>
    );
}

export default OrderDetailPage;