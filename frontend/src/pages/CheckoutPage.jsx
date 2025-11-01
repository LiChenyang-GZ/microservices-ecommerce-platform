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
        console.error('Failed to load product:', e);
        setError(e?.response?.data?.message || 'Failed to load product');
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
        alert('Order created successfully!');
        navigate('/');
      } else {
        setError(resp?.message || 'Order creation failed');
      }
    } catch (e) {
      console.error('Order creation failed:', e);
      // Use formatted error message, if not exists try to extract from response.data
      const errorMsg = e?.formattedMessage || 
                       e?.response?.data?.message || 
                       e?.message || 
                       'Order creation failed, please try again later';
      
      // If 400 error, try to display detailed validation errors
      if (e?.response?.status === 400 && e?.response?.data) {
        const data = e.response.data;
        if (data.errors && Array.isArray(data.errors)) {
          // Bean Validation error - display all field errors
          const detailedErrors = data.errors.map(err => {
            const field = err.field || err.propertyPath || 'Parameter';
            const msg = err.message || err.defaultMessage || 'Validation failed';
            return `• ${field}: ${msg}`;
          }).join('\n');
          setError(`Request parameter error:\n${detailedErrors}`);
        } else if (data.message) {
          setError(data.message);
        } else {
          setError(errorMsg);
        }
      } else {
        setError(errorMsg);
      }
    } finally {
      setLoading(false);
    }
  };

  if (!productId) return null;

  return (
    <div className="co-container">
      <button className="co-back" onClick={() => navigate(-1)}>← Back</button>

      <h1>Confirm Order</h1>

      {error && (
        <div className="co-error">
          <div style={{ fontWeight: 'bold', marginBottom: '8px' }}>⚠️ Error Message:</div>
          <div style={{ whiteSpace: 'pre-line', fontSize: '14px' }}>{error}</div>
        </div>
      )}

      {product ? (
        <div className="co-card">
          <div className="co-row">
            <div className="co-name">{product.name}</div>
            <div className="co-price">¥ {Number(product.price || 0).toFixed(2)}</div>
          </div>
          <div className="co-row">
            <label>
              Quantity:
              <input type="number" min={1} max={product.stockQuantity || 1} value={qty}
                     onChange={(e)=>setQty(e.target.value)} />
            </label>
          </div>
          <div className="co-row co-total">
            Total: <strong>¥ {total}</strong>
          </div>
          <div className="co-actions">
            <button className="co-btn" disabled={loading || (product.stockQuantity||0)<=0} onClick={submitOrder}>
              {loading ? 'Submitting...' : 'Submit Order and Pay'}
            </button>
          </div>
        </div>
      ) : (
        <div>Loading...</div>
      )}

      <style>{`
        .co-container{max-width:720px;margin:0 auto;padding:24px}
        .co-back{background:none;border:none;color:#555;cursor:pointer;margin-bottom:12px}
        .co-error{color:#b00020;background:#ffeaea;border:1px solid #ffb3b3;padding:12px;border-radius:8px;margin-bottom:12px;line-height:1.6}
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
