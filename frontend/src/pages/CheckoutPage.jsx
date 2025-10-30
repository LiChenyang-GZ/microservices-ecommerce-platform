import React, { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { checkoutAPI, productAPI } from '../services/api';
import { useAuth } from '../contexts/AuthContext';

export default function CheckoutPage() {
  const { state } = useLocation();
  const navigate = useNavigate();
  const { userId, isLoggedIn } = useAuth();

  const [product, setProduct] = useState(null);
  const [qty, setQty] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const productId = state?.productId;
  const initQty = state?.qty ?? 1;

  useEffect(() => {
    if (!isLoggedIn) {
      navigate('/login');
      return;
    }
    if (!productId) {
      navigate('/products');
      return;
    }
    setQty(initQty);
    const load = async () => {
      try {
        const p = await productAPI.getById(productId);
        setProduct(p);
      } catch (e) {
        console.error('加载商品失败:', e);
        setError(e?.response?.data?.message || '加载商品失败');
      }
    };
    load();
  }, [productId, initQty, isLoggedIn, navigate]);

  const total = useMemo(() => {
    const price = Number(product?.price || 0);
    const n = Number(qty || 1);
    return (price * n).toFixed(2);
  }, [product, qty]);

  const submitOrder = async () => {
    if (!product) return;
    setLoading(true);
    setError('');
    try {
      const resp = await checkoutAPI.createOrderWithPayment({
        userId,
        items: [{ productId: product.id, qty: Number(qty || 1) }]
      });
      if (resp?.success) {
        alert('下单成功！');
        navigate('/');
      } else {
        setError(resp?.message || '下单失败');
      }
    } catch (e) {
      console.error('下单失败:', e);
      setError(e?.response?.data?.message || '下单失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  if (!productId) return null;

  return (
    <div className="co-container">
      <button className="co-back" onClick={() => navigate(-1)}>← 返回</button>

      <h1>确认订单</h1>

      {error && <div className="co-error">{error}</div>}

      {product ? (
        <div className="co-card">
          <div className="co-row">
            <div className="co-name">{product.name}</div>
            <div className="co-price">¥ {Number(product.price || 0).toFixed(2)}</div>
          </div>
          <div className="co-row">
            <label>
              数量：
              <input type="number" min={1} max={product.stockQuantity || 1} value={qty}
                     onChange={(e)=>setQty(e.target.value)} />
            </label>
          </div>
          <div className="co-row co-total">
            总计：<strong>¥ {total}</strong>
          </div>
          <div className="co-actions">
            <button className="co-btn" disabled={loading || (product.stockQuantity||0)<=0} onClick={submitOrder}>
              {loading ? '提交中...' : '提交订单并支付'}
            </button>
          </div>
        </div>
      ) : (
        <div>正在加载...</div>
      )}

      <style>{`
        .co-container{max-width:720px;margin:0 auto;padding:24px}
        .co-back{background:none;border:none;color:#555;cursor:pointer;margin-bottom:12px}
        .co-error{color:#b00020;background:#ffeaea;border:1px solid #ffb3b3;padding:12px;border-radius:8px;margin-bottom:12px}
        .co-card{background:#fff;border:1px solid #eee;border-radius:10px;padding:16px}
        .co-row{display:flex;justify-content:space-between;align-items:center;margin:10px 0}
        .co-name{font-weight:600}
        .co-price{color:#5b21b6;font-weight:700}
        .co-total{font-size:18px}
        .co-actions{display:flex;justify-content:flex-end}
        .co-btn{background:linear-gradient(90deg,#6366f1,#8b5cf6);color:#fff;border:none;border-radius:8px;padding:10px 16px;cursor:pointer}
      `}</style>
    </div>
  );
}
