-- Initial Bank Accounts for Testing
-- Note: This will be loaded automatically by Spring Boot if spring.jpa.hibernate.ddl-auto is set to create or create-drop
-- For 'update', you may need to insert manually or use a DataInitializer component

-- Store Account (initially empty, will receive payments from customers)
INSERT INTO bank_accounts (account_number, balance, owner_name, created_at) 
VALUES ('STORE_ACCOUNT_001', 0.00, 'Online Store', CURRENT_TIMESTAMP)
ON CONFLICT (account_number) DO NOTHING;

-- Customer Account (for testing, pre-funded with $10,000)
INSERT INTO bank_accounts (account_number, balance, owner_name, created_at) 
VALUES ('CUSTOMER_ACCOUNT_001', 10000.00, 'Test Customer', CURRENT_TIMESTAMP)
ON CONFLICT (account_number) DO NOTHING;

-- Additional test accounts (optional)
INSERT INTO bank_accounts (account_number, balance, owner_name, created_at) 
VALUES ('CUSTOMER_ACCOUNT_002', 5000.00, 'Customer Two', CURRENT_TIMESTAMP)
ON CONFLICT (account_number) DO NOTHING;

INSERT INTO bank_accounts (account_number, balance, owner_name, created_at) 
VALUES ('CUSTOMER_ACCOUNT_003', 100.00, 'Customer Low Balance', CURRENT_TIMESTAMP)
ON CONFLICT (account_number) DO NOTHING;


