// Package app wires the consumer, the store and the HTTP API together.
package app

import (
	"context"
	"log/slog"
	"net"
	"time"
)

// Config comes from environment variables (no passwords in code or images).
type Config struct {
	DatabaseURL    string   // DATABASE_URL, required
	Brokers        []string // KAFKA_BROKERS, comma separated, required
	StoreURL       string   // STORE_URL, required: where to validate tokens
	HTTPAddr       string   // HTTP_ADDR, default :8085
	Group          string   // KAFKA_GROUP, default tracking
	SessionTimeout time.Duration

	// OnListening is called with the HTTP address once the server is listening.
	// Tests use it when HTTPAddr ends in :0 (random port).
	OnListening func(net.Addr)
}

// LoadConfig reads Config from getenv (os.Getenv in production).
func LoadConfig(getenv func(string) string) (Config, error) {
	return Config{}, nil
}

// Run starts everything and blocks until ctx is cancelled.
func Run(ctx context.Context, cfg Config, log *slog.Logger) error {
	<-ctx.Done()
	return nil
}
