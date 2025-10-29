import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { deliveryAPI } from '../services/api';

function DeliveryListPage() {
    const [deliveries, setDeliveries] = useState([]);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');

    // 为了演示，我们硬编码一个用户邮箱。
    // 在一个真实的应用中，这个邮箱会从用户登录状态中获取。
    const userEmail = 'grace.hopper@example.com';

    useEffect(() => {
        const fetchDeliveries = async () => {
            try {
                setIsLoading(true);
                const response = await deliveryAPI.getAllDeliveriesByEmail(userEmail);
                setDeliveries(response.data);
                setError('');
            } catch (err) {
                setError('无法加载订单列表，请稍后再试。');
                console.error(err);
            } finally {
                setIsLoading(false);
            }
        };

        fetchDeliveries();
    }, []); // 空数组意味着这个effect只在组件首次加载时运行一次

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