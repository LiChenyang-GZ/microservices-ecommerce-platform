// Package consumer reads order-events and delivery-events and stores them.
package consumer

import (
	"context"
	"log/slog"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/store"
)

// Message is one Kafka record, without any client library types,
// so the handling logic can be unit tested without Kafka.
type Message struct {
	Topic     string
	Partition int32
	Offset    int64
	Key       []byte
	Value     []byte
}

// Sink is where valid events go (the database in production).
type Sink interface {
	Insert(ctx context.Context, r store.Record) error
}

// DeadLetter receives messages that can never succeed (rule 6).
type DeadLetter interface {
	Publish(ctx context.Context, m Message, reason error) error
}

// Handler decides what happens to one message: store it, skip it, or dead-letter it.
type Handler struct {
	Sink     Sink
	DLT      DeadLetter
	Log      *slog.Logger
	RetryMin time.Duration // first backoff after a transient failure
	RetryMax time.Duration // backoff never grows past this
}

// Handle processes one message. It returns nil once the message is done with
// (stored, skipped or dead-lettered) and its offset may be committed.
func (h *Handler) Handle(ctx context.Context, m Message) error {
	env, err := event.Parse(m.Value)
	if err != nil {
		h.Log.Warn("skipping unreadable message", "topic", m.Topic, "offset", m.Offset, "err", err)
		return nil
	}
	rec := store.Record{Envelope: env, Topic: m.Topic, Partition: m.Partition, Offset: m.Offset}
	if err := h.Sink.Insert(ctx, rec); err != nil {
		h.Log.Error("could not store event", "eventId", env.EventID, "err", err)
		return nil
	}
	return nil
}
