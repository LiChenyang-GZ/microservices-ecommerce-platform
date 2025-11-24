import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import BackButton from '../components/BackButton';
import { deliveryAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';
import './DeliveryListPage.css';

function DeliveryListPage() {
    const [deliveries, setDeliveries] = useState([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');
    const { isLoggedIn, userEmail, isLoading: authLoading } = useAuth();
    const navigate = useNavigate();

    useEffect(() => {
        // If user is not logged in, redirect to login page
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }

        // If user is logged in, fetch delivery list
        if (isLoggedIn) {
            const fetchDeliveries = async () => {
                try {
                    setIsLoading(true);
                    // Use new API, no need to pass email, backend will automatically get from token
                    const response = await deliveryAPI.getMyDeliveries();
                    setDeliveries(response.data);
                    setError('');
                } catch (err) {
                    console.error('Failed to fetch delivery list:', err);
                    console.error('Error details:', {
                        status: err.response?.status,
                        statusText: err.response?.statusText,
                        data: err.response?.data,
                        message: err.message,
                        request: err.config?.url
                    });
                    
                    if (err.response?.status === 401) {
                        setError('Please login first');
                        navigate('/login');
                    } else if (err.response?.status === 500) {
                        setError('Server error, please try again later');
                    } else if (err.request && !err.response) {
                        setError('Unable to connect to server, please check network connection');
                    } else {
                        setError(`Unable to load order list: ${err.response?.data?.message || err.message || 'Unknown error'}`);
                    }
                } finally {
                    setIsLoading(false);
                }
            };

            fetchDeliveries();
        }
    }, [isLoggedIn, authLoading, navigate]); // Dependencies include login status

    const getStatusClass = (status) => {
        const statusLower = status?.toLowerCase();
        if (statusLower === 'received' || statusLower === 'delivered') return 'status-completed';
        if (statusLower === 'cancelled' || statusLower === 'canceled' || statusLower === 'lost') return 'status-cancelled';
        if (statusLower === 'created' || statusLower === 'pending') return 'status-pending';
        if (statusLower === 'pickup' || statusLower === 'in_transit') return 'status-processing';
        return 'status-default';
    };

    const formatDate = (dateString) => {
        if (!dateString) return 'N/A';
        const date = new Date(dateString);
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
    };

    if (isLoading) {
        return <div className="loading-container">Loading your deliveries...</div>;
    }

    if (error) {
        return (
            <div className="delivery-list-container">
                <BackButton fallback="/" />
                <div className="error-message">{error}</div>
            </div>
        );
    }

    return (
        <div className="delivery-list-container">
            <BackButton fallback="/" />
            <h1 className="page-title">My Deliveries</h1>
            {deliveries.length === 0 ? (
                <div className="empty-state">
                    <p>You currently have no active deliveries.</p>
                    <Link to="/products" className="btn btn-primary">
                        Browse Products
                    </Link>
                </div>
            ) : (
                <div className="deliveries-grid">
                    {deliveries.map(delivery => (
                        <div key={delivery.id} className="delivery-card">
                            <div className="delivery-header">
                                <div className="delivery-info">
                                    <span className="delivery-id">Delivery #{delivery.id}</span>
                                    {delivery.creationTime && (
                                        <span className="delivery-date">{formatDate(delivery.creationTime)}</span>
                                    )}
                                </div>
                                <span className={`delivery-status ${getStatusClass(delivery.deliveryStatus)}`}>
                                    {delivery.deliveryStatus}
                                </span>
                            </div>
                            <div className="delivery-body">
                                <div className="detail-row">
                                    <span className="label">Product:</span>
                                    <span className="value">{delivery.productName || 'N/A'}</span>
                                </div>
                                <div className="detail-row">
                                    <span className="label">Quantity:</span>
                                    <span className="value">{delivery.quantity || 1}</span>
                                </div>
                                {delivery.toAddress && (
                                    <div className="detail-row">
                                        <span className="label">Delivery To:</span>
                                        <span className="value">{delivery.toAddress}</span>
                                    </div>
                                )}
                            </div>
                            <div className="delivery-footer">
                                <Link to={`/delivery/${delivery.id}`} className="btn btn-details">
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

export default DeliveryListPage;