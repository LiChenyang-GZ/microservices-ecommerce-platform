// Package store keeps tracking events in tracking_db and reads an order's timeline.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
)

// Record is one consumed event plus where it came from in Kafka.
type Record struct {
	event.Envelope
	Topic     string
	Partition int32
	Offset    int64
}

// Entry is one line of an order's timeline.
type Entry struct {
	EventType  string
	Source     string // topic name
	OccurredAt time.Time
}

// Store talks to tracking_db.
type Store struct {
	pool *pgxpool.Pool
}

// Open connects to the database at url.
func Open(ctx context.Context, url string) (*Store, error) {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, err
	}
	return &Store{pool: pool}, nil
}

// Close releases all connections.
func (s *Store) Close() { s.pool.Close() }

// Ping checks the database is reachable.
func (s *Store) Ping(ctx context.Context) error { return s.pool.Ping(ctx) }

// event_id is a uuid primary key: Kafka delivers at least once, so the same event
// can arrive twice, and the key turns the second copy into a no-op (rule 4).
// The uuid type (not text) makes "3F1C…" and "3f1c…" the same key.
const schema = `
CREATE TABLE IF NOT EXISTS tracking_events (
    event_id        uuid        PRIMARY KEY,
    order_id        text        NOT NULL,
    event_type      text        NOT NULL,
    source_topic    text        NOT NULL,
    kafka_partition integer     NOT NULL,
    kafka_offset    bigint      NOT NULL,
    occurred_at     timestamptz NOT NULL,
    payload         jsonb       NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS tracking_events_order_time ON tracking_events (order_id, occurred_at)`

// Migrate creates the table if it does not exist yet.
func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, schema)
	return err
}

// Insert saves one event. Saving an event that is already there succeeds and
// changes nothing: the first copy is kept.
func (s *Store) Insert(ctx context.Context, r Record) error {
	_, err := s.pool.Exec(ctx, `
INSERT INTO tracking_events
    (event_id, order_id, event_type, source_topic, kafka_partition, kafka_offset, occurred_at, payload)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
ON CONFLICT (event_id) DO NOTHING`,
		r.EventID, r.OrderID, r.EventType, r.Topic, r.Partition, r.Offset, r.OccurredAt, []byte(r.Payload))
	return err
}

// Timeline returns an order's events in display order (rule 5):
// by occurredAt; at the same instant order-events before delivery-events
// (an order happens before its delivery, G4); then by Kafka offset.
// Arrival order is not used: the two topics have no order between them.
func (s *Store) Timeline(ctx context.Context, orderID string) ([]Entry, error) {
	rows, err := s.pool.Query(ctx, `
SELECT event_type, source_topic, occurred_at
FROM tracking_events
WHERE order_id = $1
ORDER BY occurred_at,
         CASE source_topic WHEN 'order-events' THEN 0 ELSE 1 END,
         kafka_offset`, orderID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (Entry, error) {
		var e Entry
		err := row.Scan(&e.EventType, &e.Source, &e.OccurredAt)
		// pgx returns timestamptz in the machine's local zone; events are defined in UTC.
		e.OccurredAt = e.OccurredAt.UTC()
		return e, err
	})
}

// Owner returns the userId from the order's OrderPlaced event; found is false if it has not arrived.
func (s *Store) Owner(ctx context.Context, orderID string) (userID int64, found bool, err error) {
	err = s.pool.QueryRow(ctx, `
SELECT (payload->>'userId')::bigint
FROM tracking_events
WHERE order_id = $1 AND event_type = 'OrderPlaced'
LIMIT 1`, orderID).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	return userID, true, nil
}
