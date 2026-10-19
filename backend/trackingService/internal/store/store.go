// Package store keeps tracking events in tracking_db and reads an order's timeline.
package store

import (
	"context"
	"time"

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

// Migrate creates the table if it does not exist yet.
func (s *Store) Migrate(ctx context.Context) error {
	return nil
}

// Insert saves one event.
func (s *Store) Insert(ctx context.Context, r Record) error {
	return nil
}

// Timeline returns an order's events in display order.
func (s *Store) Timeline(ctx context.Context, orderID string) ([]Entry, error) {
	return nil, nil
}

// Owner returns the userId from the order's OrderPlaced event; found is false if it has not arrived.
func (s *Store) Owner(ctx context.Context, orderID string) (userID int64, found bool, err error) {
	return 0, false, nil
}
