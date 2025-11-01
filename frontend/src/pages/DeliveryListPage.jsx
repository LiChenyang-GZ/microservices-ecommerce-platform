import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import BackButton from '../components/BackButton';
import { deliveryAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';

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

    if (isLoading) {
        return <div>Loading your orders...</div>;
    }

    if (error) {
        return <div className="error">{error}</div>;
    }

    return (
        <div className="container" style={{ paddingBottom: 24 }}>
            <BackButton fallback="/" />
            <h1>My Deliveries</h1>
            {deliveries.length === 0 ? (
                <p>You currently have no orders in delivery.</p>
            ) : (
                <ul className="delivery-list" style={{ maxHeight: '70vh', overflowY: 'auto', paddingRight: 8 }}>
                    {deliveries.map(delivery => (
                        <li key={delivery.id} className="delivery-item">
                            <div>
                                <strong>Product:</strong> {delivery.productName}
                                <br />
                                <strong>Status:</strong> <span className={`status status-${delivery.deliveryStatus}`}>{delivery.deliveryStatus}</span>
                            </div>
                            <Link to={`/delivery/${delivery.id}`} className="btn">
                                View Details
                            </Link>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

export default DeliveryListPage;