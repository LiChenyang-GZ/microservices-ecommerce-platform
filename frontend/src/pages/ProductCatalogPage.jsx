import React, { useEffect, useMemo, useState } from 'react';
import { productAPI } from '../services/api';
import BackButton from '../components/BackButton';
import { useNavigate } from 'react-router-dom';

function ProductCard({ product, onClick }) {
  return (
    <div className="product-card" onClick={onClick}>
      <div className="product-card__header">
        <h3 className="product-card__title">{product.name}</h3>
        <span className={`product-card__stock ${product.stockQuantity > 0 ? 'in' : 'out'}`}>
          {product.stockQuantity > 0 ? '有货' : '缺货'}
        </span>
      </div>
      <div className="product-card__body">
        <p className="product-card__desc">{product.description || '暂无描述'}</p>
      </div>
      <div className="product-card__footer">
        <span className="product-card__price">¥ {Number(product.price || 0).toFixed(2)}</span>
        <span className="product-card__sku">SKU: {product.sku || '-'}</span>
      </div>
    </div>
  );
}

export default function ProductCatalogPage() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [products, setProducts] = useState([]);
  const [query, setQuery] = useState('');
  const [onlyAvailable, setOnlyAvailable] = useState(true);
  const navigate = useNavigate();

  const fetchProducts = async () => {
    setLoading(true);
    setError('');
    try {
      const list = onlyAvailable ? await productAPI.getAvailable() : await productAPI.getAll();
      setProducts(Array.isArray(list) ? list : []);
    } catch (e) {
      console.error('加载商品失败:', e);
      setError(e?.response?.data?.message || '加载商品失败，请稍后再试');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchProducts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onlyAvailable]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return products;
    return products.filter(p =>
      (p.name || '').toLowerCase().includes(q) ||
      (p.description || '').toLowerCase().includes(q) ||
      (p.sku || '').toLowerCase().includes(q)
    );
  }, [products, query]);

  return (
    <div className="catalog-container">
      <BackButton fallback="/" />
      <div className="catalog-toolbar">
        <h1>商品目录</h1>
        <div className="catalog-actions">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索名称/描述/SKU"
            className="catalog-search"
          />
          <label className="catalog-switch">
            <input
              type="checkbox"
              checked={onlyAvailable}
              onChange={() => setOnlyAvailable(v => !v)}
            />
            <span>仅看有库存</span>
          </label>
        </div>
      </div>

      {loading && <div className="catalog-hint">正在加载商品...</div>}
      {!loading && error && <div className="catalog-error">{error}</div>}

      {!loading && !error && (
        <div className="catalog-grid">
          {filtered.length === 0 ? (
            <div className="catalog-empty">未找到符合条件的商品</div>
          ) : (
            filtered.map(p => (
              <ProductCard key={p.id} product={p} onClick={() => navigate(`/products/${p.id}`)} />
            ))
          )}
        </div>
      )}

      <style>{`
        .catalog-container { padding: 24px; max-width: 1080px; margin: 0 auto; }
        .catalog-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
        .catalog-actions { display: flex; align-items: center; gap: 12px; }
        .catalog-search { padding: 8px 12px; border: 1px solid #ddd; border-radius: 6px; min-width: 260px; }
        .catalog-switch { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
        .catalog-hint { padding: 24px; color: #555; }
        .catalog-error { padding: 16px; color: #b00020; background: #ffeaea; border: 1px solid #ffb3b3; border-radius: 6px; }
        .catalog-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }
        .catalog-empty { padding: 24px; color: #666; }
        .product-card { border: 1px solid #eee; border-radius: 10px; padding: 14px; background: #fff; cursor: pointer; transition: box-shadow .2s ease; }
        .product-card:hover { box-shadow: 0 4px 18px rgba(0,0,0,.08); }
        .product-card__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
        .product-card__title { font-size: 16px; margin: 0; }
        .product-card__stock.in { color: #0a7a29; background: #eaffef; border: 1px solid #b3f0c4; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
        .product-card__stock.out { color: #8a5300; background: #fff7e6; border: 1px solid #ffe0a3; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
        .product-card__desc { color: #666; font-size: 13px; min-height: 38px; }
        .product-card__footer { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; color: #333; font-weight: 600; }
        .product-card__price { color: #5b21b6; }
      `}</style>
    </div>
  );
}
