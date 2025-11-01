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
        console.error('Failed to load product details:', e);
        setError(e?.response?.data?.message || 'Failed to load, please try again later');
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

  if (loading) return <div className="pd-container">Loading...</div>;
  if (error) return <div className="pd-container pd-error">{error}</div>;
  if (!product) return <div className="pd-container">Product not found</div>;

  const inStock = (product.stockQuantity || 0) > 0;

  return (
    <div className="pd-container">
      <button className="pd-back" onClick={() => navigate('/products')}>← Back to List</button>
      <div className="pd-card">
        <div className="pd-main">
          <h1 className="pd-title">{product.name}</h1>
          <div className="pd-meta">
            <span className="pd-price">¥ {Number(product.price || 0).toFixed(2)}</span>
            <span className={`pd-stock ${inStock ? 'in' : 'out'}`}>{inStock ? `In Stock (${product.stockQuantity})` : 'Out of Stock'}</span>
          </div>
          <p className="pd-desc">{product.description || 'No description'}</p>
          <div className="pd-actions">
            <label className="pd-qty">
              Quantity:
              <input
                type="number"
                min={1}
                max={product.stockQuantity || 1}
                value={qty}
                onChange={(e) => setQty(e.target.value)}
              />
            </label>
            <button className="pd-btn" disabled={!inStock} onClick={goCheckout}>
              {inStock ? 'Checkout' : 'Out of Stock'}
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
