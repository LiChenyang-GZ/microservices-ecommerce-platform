import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { deliveryAPI, orderAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';

function DeliveryDetailPage() {
    const [delivery, setDelivery] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const [isCancelling, setIsCancelling] = useState(false);
    const [error, setError] = useState('');
    const { id } = useParams(); // Get ID from URL, e.g., /delivery/1
    const navigate = useNavigate();
    const { isLoggedIn, isLoading: authLoading } = useAuth();

    const fetchDelivery = useCallback(async () => {
        try {
            setIsLoading(true);
            const response = await deliveryAPI.getDeliveryById(id);
            setDelivery(response.data);
            setError('');
        } catch (err) {
            console.error('Failed to fetch order details:', err);
            if (err.response?.status === 401) {
                setError('Please login first');
                navigate('/login');
            } else if (err.response?.status === 404) {
                setError('Order not found or you do not have permission to access this order');
            } else {
                setError('Unable to load order details, please try again later.');
            }
        } finally {
            setIsLoading(false);
        }
    }, [id, navigate]);

    useEffect(() => {
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }
        if (isLoggedIn) fetchDelivery();
    }, [isLoggedIn, authLoading, fetchDelivery, navigate]);

    // Reduce race condition: poll every 2 seconds to refresh status for non-terminal states; stop for terminal states or while cancelling
    useEffect(() => {
        const terminal = ['RECEIVED', 'LOST', 'CANCELLED'];
        if (!delivery || terminal.includes(delivery?.deliveryStatus) || isCancelling) return;
        const timer = setInterval(() => { fetchDelivery(); }, 2000);
        return () => clearInterval(timer);
    }, [delivery, isCancelling, fetchDelivery]);

    const handleCancel = async () => {
        if (window.confirm('Are you sure you want to cancel this delivery task?')) {
            try {
                setIsCancelling(true);
                // Prioritize unified cancellation through StoreService (by orderId) to ensure inventory rollback and refund
                if (delivery?.orderId) {
                    await orderAPI.cancelOrder(delivery.orderId);
                } else {
                    // Fallback to old behavior: only cancel delivery (may not rollback inventory/refund)
                    await deliveryAPI.cancelDeliveryById(id);
                }
                alert('Order successfully cancelled (inventory/refund processed, if applicable)!');
                navigate('/deliveries'); // Return to list page after successful cancellation
            } catch (err) {
                console.error('Cancellation failed:', err);
                let errorMessage = 'Cancellation failed';
                if (err.response?.status === 401) {
                    errorMessage = 'Please login first';
                    navigate('/login');
                } else if (err.response?.status === 404 || err.response?.status === 403) {
                    errorMessage = 'Order not found or you do not have permission to cancel this order';
                } else if (err.response?.data) {
                    errorMessage = typeof err.response.data === 'string' 
                        ? err.response.data 
                        : err.response.data.message || 'This order may no longer be cancellable';
                }
                alert(errorMessage);
                // Re-fetch latest status on failure to prevent local display inconsistency with server
                try { await fetchDelivery(); } catch (e) { /* ignore */ }
            }
            finally { setIsCancelling(false); }
        }
    };

    if (isLoading) {
        return <div>Loading details...</div>;
    }

    if (error) {
        return <div className="error">{error}</div>;
    }

    if (!delivery) {
        return <div>Order not found.</div>;
    }

    // Only allow cancellation in specific states
    // Only allow cancellation when CREATED to avoid errors when frontend shows cancellable but backend has already entered pickup
    const canCancel = delivery.deliveryStatus === 'CREATED';

    return (
        <div className="container">
            <button onClick={() => navigate('/deliveries')} className="btn-back">← Back to List</button>
            <h1>Order Details</h1>
            <div className="delivery-details">
                <p><strong>Product:</strong> {delivery.productName}</p>
                <p><strong>Quantity:</strong> {delivery.quantity}</p>
                <p><strong>Delivery Address:</strong> {delivery.toAddress}</p>
                <p><strong>Warehouse:</strong> {delivery.fromAddress.join(', ')}</p>
                <p><strong>Created At:</strong> {new Date(delivery.creationTime).toLocaleString()}</p>
                <p><strong>Current Status:</strong> <span className={`status status-${delivery.deliveryStatus}`}>{delivery.deliveryStatus}</span></p>
            </div>

            {canCancel && (
                <button onClick={handleCancel} className="btn btn-danger" disabled={isCancelling}>
                    Cancel Order
                </button>
            )}
        </div>
    );
}

export default DeliveryDetailPage;