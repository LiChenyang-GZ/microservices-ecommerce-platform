import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import BackButton from '../components/BackButton';
import { orderAPI, paymentAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import './OrderDetailPage.css';

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

    const fetchOrderDetails = async () => {
        try {
            setIsLoading(true);
            const response = await orderAPI.getOrderWithPayment(id);

            // Transform the nested structure to flat structure
            const transformedOrder = {
                orderId: response.order?.id,
                orderDate: response.order?.createdAt,
                orderStatus: response.order?.status,
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
            setIsLoading(false);
        }
    };

    useEffect(() => {
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }
        if (isLoggedIn) {
            fetchOrderDetails();
        }
    }, [isLoggedIn, authLoading, id, navigate]);

    const handleCancelOrder = async () => {
        if (!window.confirm('Are you sure you want to cancel this order? This will process a refund if payment was made.')) {
            return;
        }

        try {
            setIsCancelling(true);
            await orderAPI.cancelOrder(id);
            alert('Order successfully cancelled! Refund has been processed.');
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
            alert('Refund request submitted successfully!');
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

            // Call the retry payment API for this order
            const result = await orderAPI.retryPayment(order.orderId);

            if (result && result.success) {
                alert('Payment successful! Your order has been confirmed.');
                await fetchOrderDetails(); // Refresh to show updated status
            }
        } catch (err) {
            console.error('Payment failed:', err);
            let errorMessage = 'Payment failed. ';

            if (err.formattedMessage) {
                errorMessage += err.formattedMessage;
            } else if (err.message) {
                errorMessage += err.message;
            }

            // Add helpful hints
            if (errorMessage.toLowerCase().includes('insufficient balance') || errorMessage.toLowerCase().includes('balance')) {
                errorMessage += '\n\nPlease add money to your account first (Home > Add Money).';
            } else if (errorMessage.toLowerCase().includes('no linked bank account')) {
                errorMessage += '\n\nPlease go to "Add Money" page to create your bank account first.';
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
        if (statusLower === 'pending') return 'status-pending';
        if (statusLower === 'processing' || statusLower === 'confirmed') return 'status-processing';
        return 'status-default';
    };

    // Determine if order can be cancelled (not allowed after pickup/shipping)
    const canCancel = order && order.orderStatus &&
        !['CANCELLED', 'CANCELED', 'COMPLETED', 'DELIVERED', 'SHIPPED', 'IN_TRANSIT', 'DELIVERING'].includes(order.orderStatus.toUpperCase());

    // Determine if refund is available - hide when in delivery or already cancelled
    // Note: Cancel button already handles refunds, so refund button is only for special cases
    const canRefund = order && order.payment &&
        order.payment.status &&
        order.payment.status.toUpperCase() === 'SUCCESS' &&
        order.orderStatus &&
        !['CANCELLED', 'CANCELED', 'REFUNDED', 'SHIPPED', 'IN_TRANSIT', 'DELIVERING', 'DELIVERED'].includes(order.orderStatus.toUpperCase());

    // Determine if payment is needed (pending payment or failed payment)
    const needsPayment = order && order.orderStatus &&
        (order.orderStatus.toUpperCase() === 'PENDING_PAYMENT' ||
         (order.payment && ['FAILED', 'PENDING'].includes(order.payment.status.toUpperCase())));

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
                                        <td>{item.productName || `Product ID: ${item.productId}`}</td>
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

            {needsPayment && (
                <div className="payment-alert">
                    <p className="alert-message">
                        ⚠️ This order is awaiting payment. Please complete the payment to confirm your order.
                    </p>
                    {order.payment?.status === 'FAILED' && (
                        <p className="alert-hint">
                            Make sure you have sufficient balance in your account. You can add money from the home page.
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
                        {isPaying ? 'Processing Payment...' : '💳 Pay Now'}
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