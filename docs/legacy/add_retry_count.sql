-- Add retry_count column to delivery table
ALTER TABLE delivery ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;

-- Verify the column was added
SELECT column_name, data_type FROM information_schema.columns 
WHERE table_name='delivery' AND column_name='retry_count';
