// Package consumer reads order-events and delivery-events and stores them.
package consumer

import (
	"context"
	"errors"
	"fmt"
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

	// Stopping, if set, is closed when the service is shutting down. A retry wait
	// ends as soon as it closes, even though the work context is still alive.
	Stopping <-chan struct{}
}

// Defaults when RetryMin / RetryMax are zero.
const (
	defaultRetryMin = 200 * time.Millisecond
	defaultRetryMax = 10 * time.Second
)

// Handle processes one message. It returns nil once the message is done with
// (stored, skipped or dead-lettered) and its offset may be committed.
// It returns ctx.Err() if ctx is cancelled first: the message is NOT done.
//
// The two kinds of failure (rule 6, ADR-08):
//   - the message itself is broken: it can never succeed, so it goes to the DLT
//     and the partition moves on;
//   - anything else (database down, DLT unreachable): retry in place with backoff.
//     The partition waits, which keeps the order, and nothing is skipped.
func (h *Handler) Handle(ctx context.Context, m Message) error {
	env, err := event.Parse(m.Value)
	if err == nil {
		topic, known := event.TopicOf(env.EventType)
		if !known {
			// Not a broken message (contracts README §2): a newer producer may have added a type.
			// Skip it; a replay after upgrading tracking would pick it up.
			h.Log.Info("skipping unknown event type", "eventType", env.EventType, "topic", m.Topic, "offset", m.Offset)
			return nil
		}
		if topic != m.Topic {
			err = fmt.Errorf("%w: %s belongs on %s, not %s", event.ErrInvalid, env.EventType, topic, m.Topic)
		}
	}
	if err != nil {
		return h.deadLetter(ctx, m, err)
	}

	rec := store.Record{Envelope: env, Topic: m.Topic, Partition: m.Partition, Offset: m.Offset}
	err = h.retry(ctx, "store event", func() error { return h.Sink.Insert(ctx, rec) })
	if errors.Is(err, store.ErrRejected) {
		// The envelope was fine but the database will never accept the data
		// (e.g. a NUL character): just as broken as an unparseable message.
		return h.deadLetter(ctx, m, err)
	}
	return err
}

// deadLetter sends m to its DLT, retrying until Kafka confirms.
func (h *Handler) deadLetter(ctx context.Context, m Message, reason error) error {
	h.Log.Warn("dead-lettering message", "topic", m.Topic, "partition", m.Partition, "offset", m.Offset, "reason", reason)
	return h.retry(ctx, "publish to DLT", func() error { return h.DLT.Publish(ctx, m, reason) })
}

// retry runs op until it succeeds, doubling the wait each time up to RetryMax.
// Waiting is a select on ctx, not time.Sleep, so a shutdown interrupts it at once.
func (h *Handler) retry(ctx context.Context, what string, op func() error) error {
	wait, maxWait := h.RetryMin, h.RetryMax
	if wait <= 0 {
		wait = defaultRetryMin
	}
	if maxWait <= 0 {
		maxWait = defaultRetryMax
	}
	for attempt := 1; ; attempt++ {
		err := op()
		if err == nil {
			return nil
		}
		if errors.Is(err, store.ErrRejected) {
			return err // permanent: retrying cannot help, the caller dead-letters it
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		h.Log.Warn(what+" failed, will retry", "attempt", attempt, "wait", wait, "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-h.Stopping: // nil channel when unset: never ready
			return context.Canceled
		case <-time.After(wait):
		}
		wait = min(wait*2, maxWait)
	}
}
