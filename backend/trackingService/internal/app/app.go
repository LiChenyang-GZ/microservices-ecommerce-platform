// Package app wires the consumer, the store and the HTTP API together.
package app

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/auth"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/consumer"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/httpapi"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/store"
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
	cfg := Config{
		DatabaseURL: getenv("DATABASE_URL"),
		StoreURL:    getenv("STORE_URL"),
		HTTPAddr:    getenv("HTTP_ADDR"),
		Group:       getenv("KAFKA_GROUP"),
	}
	if b := getenv("KAFKA_BROKERS"); b != "" {
		cfg.Brokers = strings.Split(b, ",")
	}
	if d, err := time.ParseDuration(getenv("KAFKA_SESSION_TIMEOUT")); err == nil {
		cfg.SessionTimeout = d
	}
	if cfg.HTTPAddr == "" {
		cfg.HTTPAddr = ":8085"
	}
	if cfg.Group == "" {
		cfg.Group = "tracking"
	}
	return cfg, nil
}

// Run starts everything and blocks until ctx is cancelled.
func Run(ctx context.Context, cfg Config, log *slog.Logger) error {
	db, err := store.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := db.Migrate(ctx); err != nil {
		return err
	}

	ln, err := net.Listen("tcp", cfg.HTTPAddr)
	if err != nil {
		return err
	}
	if cfg.OnListening != nil {
		cfg.OnListening(ln.Addr())
	}
	api := httpapi.New(db, &auth.Client{BaseURL: cfg.StoreURL}, log)
	go http.Serve(ln, api)

	return consumer.Run(ctx, consumer.Config{
		Brokers:        cfg.Brokers,
		Group:          cfg.Group,
		Topics:         []string{event.OrderEvents, event.DeliveryEvents},
		SessionTimeout: cfg.SessionTimeout,
		Hooks:          crashHooks(),
	}, db, log)
}
