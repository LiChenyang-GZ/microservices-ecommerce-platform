import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { productAPI } from '../services/api';

export default function ProductDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [product, setProduct] = useState(null);
  const [qty, setQty] = useState(1);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      setError('');
      try {
        const data = await productAPI.getById(id);
        setProduct(data);
      } catch (e) {
        console.error('加载商品详情失败:', e);
        setError(e?.response?.data?.message || '加载失败，请稍后再试');
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [id]);

  const goCheckout = () => {
    if (!product) return;
    const count = Math.max(1, parseInt(qty, 10) || 1);
    navigate('/checkout', { state: { productId: product.id, qty: count } });
  };

  if (loading) return <div className="pd-container">正在加载...</div>;
  if (error) return <div className="pd-container pd-error">{error}</div>;
  if (!product) return <div className="pd-container">未找到该商品</div>;

  const inStock = (product.stockQuantity || 0) > 0;

  return (
    <div className="pd-container">
      <button className="pd-back" onClick={() => navigate('/products')}>← 返回列表</button>
      <div className="pd-card">
        <div className="pd-main">
          <h1 className="pd-title">{product.name}</h1>
          <div className="pd-meta">
            <span className="pd-price">¥ {Number(product.price || 0).toFixed(2)}</span>
            <span className={`pd-stock ${inStock ? 'in' : 'out'}`}>{inStock ? `有货（${product.stockQuantity}）` : '缺货'}</span>
          </div>
          <p className="pd-desc">{product.description || '暂无描述'}</p>
          <div className="pd-actions">
            <label className="pd-qty">
              数量：
              <input
                type="number"
                min={1}
                max={product.stockQuantity || 1}
                value={qty}
                onChange={(e) => setQty(e.target.value)}
              />
            </label>
            <button className="pd-btn" disabled={!inStock} onClick={goCheckout}>
              {inStock ? '去结算' : '缺货'}
            </button>
          </div>
        </div>
      </div>

      <style>{`
        .pd-container { max-width: 960px; margin: 0 auto; padding: 24px; }
        .pd-back { background: none; border: none; color: #555; cursor: pointer; margin-bottom: 12px; }
        .pd-card { background: #fff; border: 1px solid #eee; border-radius: 10px; padding: 20px; }
        .pd-title { margin: 0 0 8px; }
        .pd-meta { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
        .pd-price { color: #5b21b6; font-weight: 700; }
        .pd-stock.in { color: #0a7a29; background: #eaffef; border: 1px solid #b3f0c4; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
        .pd-stock.out { color: #8a5300; background: #fff7e6; border: 1px solid #ffe0a3; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
        .pd-desc { color: #555; }
        .pd-actions { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
        .pd-qty input { width: 88px; padding: 6px 8px; border: 1px solid #ddd; border-radius: 6px; }
        .pd-btn { background: linear-gradient(90deg,#6366f1,#8b5cf6); color:#fff; border:none; border-radius:8px; padding:8px 14px; cursor:pointer; }
        .pd-error { color: #b00020; }
      `}</style>
    </div>
  );
}
