import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { deliveryAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';

function DeliveryListPage() {
    const [deliveries, setDeliveries] = useState([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');
    const { isLoggedIn, userEmail, isLoading: authLoading } = useAuth();
    const navigate = useNavigate();

    useEffect(() => {
        // 如果用户未登录，跳转到登录页
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }

        // 如果用户已登录，获取配送列表
        if (isLoggedIn) {
            const fetchDeliveries = async () => {
                try {
                    setIsLoading(true);
                    // 使用新的 API，不需要传入 email，后端会自动从 token 获取
                    const response = await deliveryAPI.getMyDeliveries();
                    setDeliveries(response.data);
                    setError('');
                } catch (err) {
                    console.error('获取配送列表失败:', err);
                    console.error('错误详情:', {
                        status: err.response?.status,
                        statusText: err.response?.statusText,
                        data: err.response?.data,
                        message: err.message,
                        request: err.config?.url
                    });
                    
                    if (err.response?.status === 401) {
                        setError('请先登录');
                        navigate('/login');
                    } else if (err.response?.status === 500) {
                        setError('服务器错误，请稍后再试');
                    } else if (err.request && !err.response) {
                        setError('无法连接到服务器，请检查网络连接');
                    } else {
                        setError(`无法加载订单列表: ${err.response?.data?.message || err.message || '未知错误'}`);
                    }
                } finally {
                    setIsLoading(false);
                }
            };

            fetchDeliveries();
        }
    }, [isLoggedIn, authLoading, navigate]); // 依赖项包含登录状态

    if (isLoading) {
        return <div>正在加载您的订单...</div>;
    }

    if (error) {
        return <div className="error">{error}</div>;
    }

    return (
        <div className="container">
            <h1>我的配送</h1>
            {deliveries.length === 0 ? (
                <p>您当前没有配送中的订单。</p>
            ) : (
                <ul className="delivery-list">
                    {deliveries.map(delivery => (
                        <li key={delivery.id} className="delivery-item">
                            <div>
                                <strong>商品:</strong> {delivery.productName}
                                <br />
                                <strong>状态:</strong> <span className={`status status-${delivery.deliveryStatus}`}>{delivery.deliveryStatus}</span>
                            </div>
                            <Link to={`/delivery/${delivery.id}`} className="btn">
                                查看详情
                            </Link>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

export default DeliveryListPage;