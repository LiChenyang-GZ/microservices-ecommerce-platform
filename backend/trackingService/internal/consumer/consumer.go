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

// ShutdownGrace is how long the record in hand may keep running after ctx is
// cancelled. Kubernetes waits 30s after SIGTERM before killing the pod.
const ShutdownGrace = 10 * time.Second

// Run consumes until ctx is cancelled, then finishes the record in hand,
// commits what was processed, leaves the group and returns nil.
//
// Offsets are committed by hand, only for records that are done
// (stored, skipped or dead-lettered): write first, commit second (rule 4).
// A crash between the two means the record comes again; the database ignores
// the duplicate.
func Run(ctx context.Context, cfg Config, sink Sink, log *slog.Logger) error {
	opts := []kgo.Opt{
		kgo.SeedBrokers(cfg.Brokers...),
		kgo.ConsumerGroup(cfg.Group),
		kgo.ConsumeTopics(cfg.Topics...),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()), // a new group reads from the beginning
		kgo.DisableAutoCommit(),                           // we commit after the work is done, see below
		// Hold rebalances while a batch is being processed; AllowRebalance below
		// lets them happen once the batch is committed, so a partition never moves
		// to another instance with processed-but-uncommitted records.
		kgo.BlockRebalanceOnPoll(),
	}
	if cfg.SessionTimeout > 0 {
		opts = append(opts, kgo.SessionTimeout(cfg.SessionTimeout))
	}
	cl, err := kgo.NewClient(opts...)
	if err != nil {
		return err
	}
	// Leave the group on exit so another instance takes over at once. With
	// BlockRebalanceOnPoll a plain Close would wait forever for AllowRebalance.
	defer cl.CloseAllowingRebalance()

	// work is the context the record in hand runs with. It survives the shutdown
	// signal for up to ShutdownGrace, so a half-done write can finish.
	work, cancelWork := context.WithCancel(context.WithoutCancel(ctx))
	defer cancelWork()
	stopGrace := context.AfterFunc(ctx, func() { time.AfterFunc(ShutdownGrace, cancelWork) })
	defer stopGrace()

	var current Message
	h := &Handler{
		Sink:     &hookedSink{Sink: sink, hooks: cfg.Hooks, msg: &current},
		DLT:      &kafkaDLT{cl: cl, group: cfg.Group}, // the same client can also produce
		Log:      log,
		Stopping: ctx.Done(), // a shutdown ends a retry wait at once instead of after the grace period
	}

	for {
		fetches := cl.PollFetches(ctx)
		if ctx.Err() != nil {
			return nil // shutting down; nothing in hand
		}
		fetches.EachError(func(topic string, partition int32, err error) {
			log.Error("fetch failed", "topic", topic, "partition", partition, "err", err)
		})

		var done []*kgo.Record
		for _, r := range fetches.Records() {
			if ctx.Err() != nil {
				break // shutting down: do not start the next record
			}
			current = Message{Topic: r.Topic, Partition: r.Partition, Offset: r.Offset, Key: r.Key, Value: r.Value}
			if err := h.Handle(work, current); err != nil {
				break // only returns an error when stopping; this record is not done
			}
			done = append(done, r)
		}
		if len(done) > 0 {
			// A failed commit is safe: those records are delivered again and de-duplicated.
			if err := cl.CommitRecords(work, done...); err != nil {
				log.Warn("offset commit failed; records will be redelivered", "err", err)
			}
		}
		cl.AllowRebalance()
		if ctx.Err() != nil {
			return nil
		}
	}
}
