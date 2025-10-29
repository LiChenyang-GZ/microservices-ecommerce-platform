import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { deliveryAPI } from '../services/api';

function DeliveryDetailPage() {
    const [delivery, setDelivery] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const [error, setError] = useState('');
    const { id } = useParams(); // 从URL中获取ID，例如 /delivery/1
    const navigate = useNavigate();
    const userEmail = 'grace.hopper@example.com';
    useEffect(() => {
        const fetchDelivery = async () => {
            try {
                setIsLoading(true);
                const response = await deliveryAPI.getDeliveryById(id);
                setDelivery(response.data);
                setError('');
            } catch (err) {
                setError('无法加载订单详情。');
                console.error(err);
            } finally {
                setIsLoading(false);
            }
        };

        fetchDelivery();
    }, [id]); // 当ID变化时，重新获取数据

    const handleCancel = async () => {
        if (window.confirm('您确定要取消这个配送任务吗？')) {
            try {
                await deliveryAPI.cancelDeliveryById(id);
                alert('订单已成功取消！');
                navigate('/'); // 取消成功后返回列表页
            } catch (err) {
                alert('取消失败：' + (err.response?.data || '该订单可能已无法取消。'));
                console.error(err);
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
            <button onClick={() => navigate('/')} className="btn-back">← 返回列表</button>
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