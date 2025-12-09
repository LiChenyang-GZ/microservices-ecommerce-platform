import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import BackButton from '../components/BackButton';
import { orderAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import './MyOrdersPage.css';

function MyOrdersPage() {
    const [orders, setOrders] = useState([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');
    const { isLoggedIn, userId, isLoading: authLoading } = useAuth();
    const navigate = useNavigate();

    useEffect(() => {
        // If user is not logged in, redirect to login page
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }

        // If user is logged in, fetch order list
        if (isLoggedIn && userId) {
            const fetchOrders = async () => {
                try {
                    setIsLoading(true);
                    const response = await orderAPI.getUserOrdersWithPayment(userId);

                    // Transform the nested structure to flat structure
                    const transformedOrders = response.map(item => ({
                        orderId: item.order?.id,
                        orderDate: item.order?.createdAt,
                        orderStatus: item.order?.status,
                        totalAmount: item.order?.totalAmount,
                        items: item.order ? [{
                            productId: item.order.productId,
                            productName: item.order.productName,
                            quantity: item.order.quantity,
                            unitPrice: item.order.unitPrice
                        }] : [],
                        payment: item.payment
                    }));

                    setOrders(transformedOrders);
                    setError('');
                } catch (err) {
                    console.error('Failed to fetch order list:', err);

                    if (err.response?.status === 401) {
                        setError('Please login first');
                        navigate('/login');
                    } else if (err.response?.status === 500) {
                        setError('Server error, please try again later');
                    } else if (err.request && !err.response) {
                        setError('Unable to connect to server, please check network connection');
                    } else {
                        setError(`Unable to load order list: ${err.formattedMessage || err.message || 'Unknown error'}`);
                    }
                } finally {
                    setIsLoading(false);
                }
            };

            fetchOrders();
        }
    }, [isLoggedIn, userId, authLoading, navigate]);

    const getStatusClass = (status) => {
        const statusLower = status?.toLowerCase();
        if (statusLower === 'completed' || statusLower === 'delivered') return 'status-completed';
        if (statusLower === 'cancelled' || statusLower === 'canceled') return 'status-cancelled';
        if (statusLower === 'pending') return 'status-pending';
        if (statusLower === 'processing' || statusLower === 'confirmed') return 'status-processing';
        return 'status-default';
    };

    const formatDate = (dateString) => {
        if (!dateString) return 'N/A';
        const date = new Date(dateString);
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
    };

    const formatPrice = (price) => {
        return `$${parseFloat(price || 0).toFixed(2)}`;
    };

    if (isLoading) {
        return <div className="loading-container">Loading your orders...</div>;
    }

    if (error) {
        return (
            <div className="container">
                <BackButton fallback="/" />
                <div className="error-message">{error}</div>
            </div>
        );
    }

    return (
        <div className="my-orders-container">
            <BackButton fallback="/" />
            <h1 className="page-title">My Orders</h1>
            {orders.length === 0 ? (
                <div className="empty-state">
                    <p>You haven't placed any orders yet.</p>
                    <Link to="/products" className="btn btn-primary">
                        Browse Products
                    </Link>
                </div>
            ) : (
                <div className="orders-list">
                    {orders.map(order => (
                        <div key={order.orderId} className="order-card">
                            <div className="order-header">
                                <div className="order-info">
                                    <span className="order-id">Order #{order.orderId}</span>
                                    <span className="order-date">{formatDate(order.orderDate)}</span>
                                </div>
                                <span className={`order-status ${getStatusClass(order.orderStatus)}`}>
                                    {order.orderStatus}
                                </span>
                            </div>
                            <div className="order-body">
                                <div className="order-items">
                                    <strong>Items:</strong>
                                    {order.items && order.items.length > 0 ? (
                                        <ul className="items-list">
                                            {order.items.map((item, index) => (
                                                <li key={index}>
                                                    {item.productName || `Product ID: ${item.productId}`}
                                                    {item.quantity > 1 && ` (x${item.quantity})`}
                                                </li>
                                            ))}
                                        </ul>
                                    ) : (
                                        <span> No item details available</span>
                                    )}
                                </div>
                                <div className="order-details">
                                    <div className="detail-row">
                                        <span className="label">Total Amount:</span>
                                        <span className="value price">{formatPrice(order.totalAmount)}</span>
                                    </div>
                                    {order.payment && (
                                        <div className="detail-row">
                                            <span className="label">Payment Status:</span>
                                            <span className={`value payment-status ${getStatusClass(order.payment.status)}`}>
                                                {order.payment.status}
                                            </span>
                                        </div>
                                    )}
                                </div>
                            </div>
                            <div className="order-footer">
                                <Link to={`/orders/${order.orderId}`} className="btn btn-details">
                                    View Details
                                </Link>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

export default MyOrdersPage;
