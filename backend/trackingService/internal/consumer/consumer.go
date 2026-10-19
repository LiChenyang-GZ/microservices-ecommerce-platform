package consumer

import (
	"context"
	"log/slog"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/store"
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

// hookedSink calls the hooks around every Insert.
type hookedSink struct {
	Sink
	hooks Hooks
	msg   *Message // the message being handled right now
}

func (s *hookedSink) Insert(ctx context.Context, r store.Record) error {
	if s.hooks.BeforeStore != nil {
		s.hooks.BeforeStore(*s.msg)
	}
	err := s.Sink.Insert(ctx, r)
	if err == nil && s.hooks.AfterStore != nil {
		s.hooks.AfterStore(*s.msg)
	}
	return err
}

// Run consumes until ctx is cancelled.
func Run(ctx context.Context, cfg Config, sink Sink, log *slog.Logger) error {
	opts := []kgo.Opt{
		kgo.SeedBrokers(cfg.Brokers...),
		kgo.ConsumerGroup(cfg.Group),
		kgo.ConsumeTopics(cfg.Topics...),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()), // a new group reads from the beginning
	}
	if cfg.SessionTimeout > 0 {
		opts = append(opts, kgo.SessionTimeout(cfg.SessionTimeout))
	}
	cl, err := kgo.NewClient(opts...)
	if err != nil {
		return err
	}
	defer cl.Close()

	var current Message
	h := &Handler{
		Sink: &hookedSink{Sink: sink, hooks: cfg.Hooks, msg: &current},
		DLT:  &kafkaDLT{cl: cl, group: cfg.Group}, // the same client can also produce
		Log:  log,
	}

	for {
		fetches := cl.PollFetches(ctx)
		if ctx.Err() != nil {
			return nil
		}
		fetches.EachError(func(topic string, partition int32, err error) {
			log.Error("fetch failed", "topic", topic, "partition", partition, "err", err)
		})
		fetches.EachRecord(func(r *kgo.Record) {
			current = Message{Topic: r.Topic, Partition: r.Partition, Offset: r.Offset, Key: r.Key, Value: r.Value}
			_ = h.Handle(ctx, current)
		})
	}
}
