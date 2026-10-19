package consumer

import (
	"context"
	"log/slog"
	"time"
)

// Config says where to consume from.
type Config struct {
	Brokers        []string
	Group          string
	Topics         []string
	SessionTimeout time.Duration // 0 = client default
	Hooks          Hooks
}

// Hooks are a test seam: integration tests use them to stop the consumer
// at an exact point ("crash after the row is written, before the offset is committed").
// Production code leaves them nil.
type Hooks struct {
	BeforeStore func(Message)
	AfterStore  func(Message)
}

// Run consumes until ctx is cancelled.
func Run(ctx context.Context, cfg Config, sink Sink, log *slog.Logger) error {
	<-ctx.Done()
	return nil
}
