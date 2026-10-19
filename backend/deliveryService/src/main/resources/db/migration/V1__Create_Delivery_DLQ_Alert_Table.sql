-- Create delivery_dead_letter_alert table
CREATE TABLE IF NOT EXISTS delivery_dead_letter_alert (
    id BIGSERIAL PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    severity VARCHAR(50) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    alert_timestamp TIMESTAMP NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT false,
    resolved_at TIMESTAMP
);

-- Create index on delivery_id for faster queries
CREATE INDEX IF NOT EXISTS idx_delivery_id ON delivery_dead_letter_alert(delivery_id);

-- Create index on alert_timestamp for sorting
CREATE INDEX IF NOT EXISTS idx_alert_timestamp ON delivery_dead_letter_alert(alert_timestamp DESC);
