import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { deliveryAPI, orderAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';

function DeliveryDetailPage() {
    const [delivery, setDelivery] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const [isCancelling, setIsCancelling] = useState(false);
    const [error, setError] = useState('');
    const { id } = useParams(); // 从URL中获取ID，例如 /delivery/1
    const navigate = useNavigate();
    const { isLoggedIn, isLoading: authLoading } = useAuth();

    const fetchDelivery = useCallback(async () => {
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
    }, [id, navigate]);

    useEffect(() => {
        if (!authLoading && !isLoggedIn) {
            navigate('/login');
            return;
        }
        if (isLoggedIn) fetchDelivery();
    }, [isLoggedIn, authLoading, fetchDelivery, navigate]);

    // 降低竞态：对于非终态，2秒轮询一次刷新状态；终态或取消中则停止
    useEffect(() => {
        const terminal = ['RECEIVED', 'LOST', 'CANCELLED'];
        if (!delivery || terminal.includes(delivery?.deliveryStatus) || isCancelling) return;
        const timer = setInterval(() => { fetchDelivery(); }, 2000);
        return () => clearInterval(timer);
    }, [delivery, isCancelling, fetchDelivery]);

    const handleCancel = async () => {
        if (window.confirm('您确定要取消这个配送任务吗？')) {
            try {
                setIsCancelling(true);
                // 优先通过 StoreService 统一取消（按 orderId），确保回退库存与退款
                if (delivery?.orderId) {
                    await orderAPI.cancelOrder(delivery.orderId);
                } else {
                    // 回退到旧行为：仅取消配送（可能无法回退库存/退款）
                    await deliveryAPI.cancelDeliveryById(id);
                }
                alert('订单已成功取消（已联动库存/退款处理，如适用）！');
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
                // 失败时重新拉取最新状态，防止本地显示与服务端不一致
                try { await fetchDelivery(); } catch (e) { /* ignore */ }
            }
            finally { setIsCancelling(false); }
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
    // 仅在 CREATED 时允许取消，避免前端仍显示可取消但后端已进入取件导致报错
    const canCancel = delivery.deliveryStatus === 'CREATED';

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
                <button onClick={handleCancel} className="btn btn-danger" disabled={isCancelling}>
                    取消订单
                </button>
            )}
        </div>
    );
}

export default DeliveryDetailPage;