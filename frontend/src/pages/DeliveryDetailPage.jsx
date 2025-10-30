import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { deliveryAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';

function DeliveryDetailPage() {
    const [delivery, setDelivery] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');
    const { id } = useParams(); // 从URL中获取ID，例如 /delivery/1
    const navigate = useNavigate();
    const { isLoggedIn, isLoading: authLoading } = useAuth();

    useEffect(() => {
        // 如果用户未登录，跳转到登录页
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }

        // 如果用户已登录，获取订单详情
        if (isLoggedIn) {
            const fetchDelivery = async () => {
                try {
                    setIsLoading(true);
                    const response = await deliveryAPI.getDeliveryById(id);
                    setDelivery(response.data);
                    setError('');
                } catch (err) {
                    console.error('获取订单详情失败:', err);
                    if (err.response?.status === 401) {
                        setError('请先登录');
                        navigate('/login');
                    } else if (err.response?.status === 404) {
                        setError('订单不存在或您无权访问此订单');
                    } else {
                        setError('无法加载订单详情，请稍后再试。');
                    }
                } finally {
                    setIsLoading(false);
                }
            };

            fetchDelivery();
        }
    }, [id, isLoggedIn, authLoading, navigate]); // 依赖项包含登录状态

    const handleCancel = async () => {
        if (window.confirm('您确定要取消这个配送任务吗？')) {
            try {
                await deliveryAPI.cancelDeliveryById(id);
                alert('订单已成功取消！');
                navigate('/deliveries'); // 取消成功后返回列表页
            } catch (err) {
                console.error('取消失败:', err);
                let errorMessage = '取消失败';
                if (err.response?.status === 401) {
                    errorMessage = '请先登录';
                    navigate('/login');
                } else if (err.response?.status === 404 || err.response?.status === 403) {
                    errorMessage = '订单不存在或您无权取消此订单';
                } else if (err.response?.data) {
                    errorMessage = typeof err.response.data === 'string' 
                        ? err.response.data 
                        : err.response.data.message || '该订单可能已无法取消';
                }
                alert(errorMessage);
            }
        }
    };

    if (isLoading) {
        return <div>正在加载详情...</div>;
    }

    if (error) {
        return <div className="error">{error}</div>;
    }

    if (!delivery) {
        return <div>未找到该订单。</div>;
    }

    // 只有在特定状态下才允许取消
    const canCancel = delivery.deliveryStatus === 'CREATED' || delivery.deliveryStatus === 'PICKED_UP';

    return (
        <div className="container">
            <button onClick={() => navigate('/deliveries')} className="btn-back">← 返回列表</button>
            <h1>订单详情</h1>
            <div className="delivery-details">
                <p><strong>商品:</strong> {delivery.productName}</p>
                <p><strong>数量:</strong> {delivery.quantity}</p>
                <p><strong>收货地址:</strong> {delivery.toAddress}</p>
                <p><strong>发货仓库:</strong> {delivery.fromAddress.join(', ')}</p>
                <p><strong>创建时间:</strong> {new Date(delivery.creationTime).toLocaleString()}</p>
                <p><strong>当前状态:</strong> <span className={`status status-${delivery.deliveryStatus}`}>{delivery.deliveryStatus}</span></p>
            </div>

            {canCancel && (
                <button onClick={handleCancel} className="btn btn-danger">
                    取消订单
                </button>
            )}
        </div>
    );
}

export default DeliveryDetailPage;