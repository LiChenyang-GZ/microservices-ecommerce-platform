-- 訂單與商品管理功能資料庫遷移腳本
-- 適用於PostgreSQL

-- 創建商品表
CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    description VARCHAR(500),
    stock_quantity INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 創建訂單表
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PLACED',
    total_amount DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 創建訂單項目表
CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    qty INTEGER NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    total_price DECIMAL(10,2) NOT NULL
);

-- 創建索引以提高查詢性能
CREATE INDEX IF NOT EXISTS idx_products_name ON products(name);
CREATE INDEX IF NOT EXISTS idx_products_price ON products(price);
CREATE INDEX IF NOT EXISTS idx_products_stock ON products(stock_quantity);
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product_id ON order_items(product_id);

-- 插入示例商品數據
INSERT INTO products (name, price, description, stock_quantity) VALUES
('iPhone 14', 999.99, '最新款iPhone，配備A16仿生晶片', 100),
('MacBook Pro', 1999.99, '專業筆記型電腦，M2晶片', 50),
('AirPods Pro', 249.99, '無線耳機，主動降噪功能', 200),
('iPad Air', 599.99, '平板電腦，M1晶片', 75),
('Apple Watch', 399.99, '智能手錶，健康監測功能', 150)
ON CONFLICT DO NOTHING;

-- 創建觸發器自動更新updated_at欄位
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- 為products表創建觸發器
DROP TRIGGER IF EXISTS update_products_updated_at ON products;
CREATE TRIGGER update_products_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 為orders表創建觸發器
DROP TRIGGER IF EXISTS update_orders_updated_at ON orders;
CREATE TRIGGER update_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 創建視圖：商品庫存狀態
CREATE OR REPLACE VIEW product_stock_status AS
SELECT 
    id,
    name,
    price,
    stock_quantity,
    CASE 
        WHEN stock_quantity > 0 THEN 'AVAILABLE'
        WHEN stock_quantity = 0 THEN 'OUT_OF_STOCK'
        ELSE 'LOW_STOCK'
    END as stock_status,
    created_at,
    updated_at
FROM products;

-- 創建視圖：訂單統計
CREATE OR REPLACE VIEW order_statistics AS
SELECT 
    o.id,
    o.user_id,
    o.status,
    o.total_amount,
    COUNT(oi.id) as item_count,
    o.created_at,
    o.updated_at
FROM orders o
LEFT JOIN order_items oi ON o.id = oi.order_id
GROUP BY o.id, o.user_id, o.status, o.total_amount, o.created_at, o.updated_at;

-- 創建函數：檢查商品庫存
CREATE OR REPLACE FUNCTION check_product_stock(product_id BIGINT, required_qty INTEGER)
RETURNS BOOLEAN AS $$
DECLARE
    current_stock INTEGER;
BEGIN
    SELECT stock_quantity INTO current_stock
    FROM products
    WHERE id = product_id;
    
    IF current_stock IS NULL THEN
        RETURN FALSE;
    END IF;
    
    RETURN current_stock >= required_qty;
END;
$$ LANGUAGE plpgsql;

-- 創建函數：更新商品庫存
CREATE OR REPLACE FUNCTION update_product_stock(product_id BIGINT, new_qty INTEGER)
RETURNS BOOLEAN AS $$
BEGIN
    UPDATE products 
    SET stock_quantity = new_qty, updated_at = CURRENT_TIMESTAMP
    WHERE id = product_id;
    
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

-- 創建函數：獲取用戶訂單總金額
CREATE OR REPLACE FUNCTION get_user_total_order_amount(user_id BIGINT)
RETURNS DECIMAL(10,2) AS $$
DECLARE
    total_amount DECIMAL(10,2);
BEGIN
    SELECT COALESCE(SUM(total_amount), 0) INTO total_amount
    FROM orders
    WHERE user_id = user_id AND status != 'CANCELLED';
    
    RETURN total_amount;
END;
$$ LANGUAGE plpgsql;

-- 添加註釋
COMMENT ON TABLE products IS '商品表';
COMMENT ON TABLE orders IS '訂單表';
COMMENT ON TABLE order_items IS '訂單項目表';
COMMENT ON VIEW product_stock_status IS '商品庫存狀態視圖';
COMMENT ON VIEW order_statistics IS '訂單統計視圖';
COMMENT ON FUNCTION check_product_stock IS '檢查商品庫存是否足夠';
COMMENT ON FUNCTION update_product_stock IS '更新商品庫存';
COMMENT ON FUNCTION get_user_total_order_amount IS '獲取用戶訂單總金額';

-- 完成遷移
SELECT 'Database migration completed successfully!' as status;
