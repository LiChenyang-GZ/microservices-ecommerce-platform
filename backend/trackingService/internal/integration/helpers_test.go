//go:build integration

package integration

// 集成测试共用的小工具：发消息、改写样例、启动消费者、等待条件成立。

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/consumer"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/examples"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/store"
)

var quietLog = slog.New(slog.NewTextHandler(io.Discard, nil))

var orderSeq atomic.Int64 // 每次调用 newOrderID 加一，保证同一次测试运行里不重复

// newOrderID 给每个测试一个独一无二的订单号，这样共用一个 Kafka 和一个库也不会互相干扰。
func newOrderID() string {
	return fmt.Sprintf("%d%03d", time.Now().Unix(), orderSeq.Add(1))
}

// examplesFor 读 11 个样例，把 orderId 换成 orderID、eventId 换成新的随机 UUID。
func examplesFor(t *testing.T, orderID string) []examples.Example {
	t.Helper()
	all := examples.All(t)
	for i := range all {
		var m map[string]any // 先解成通用的 map，改两个字段，再编码回去
		if err := json.Unmarshal(all[i].JSON, &m); err != nil {
			t.Fatalf("decode %s: %v", all[i].Name, err)
		}
		m["orderId"] = orderID
		m["eventId"] = uuid.NewString()
		out, err := json.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		all[i].JSON = out
	}
	return all
}

// produce 把一条消息同步发到 Kafka（等 broker 确认才返回），key = orderId（规则 5）。
func produce(t *testing.T, topic, key string, value []byte) {
	t.Helper()
	cl, err := kgo.NewClient(kgo.SeedBrokers(brokers...))
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	r := &kgo.Record{Topic: topic, Key: []byte(key), Value: value}
	if err := cl.ProduceSync(ctx, r).FirstErr(); err != nil {
		t.Fatalf("produce to %s: %v", topic, err)
	}
}

// openStore 连上测试库并建表。
func openStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(context.Background(), dbURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	if err := s.Migrate(context.Background()); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	return s
}

// running 是一个在后台跑的消费者。
type running struct {
	cancel context.CancelFunc // 调用它 = 让消费者停下（相当于收到 SIGTERM）
	done   chan error         // 消费者的 Run 返回时，返回值会送进这里
}

// startConsumer 在一个新的 goroutine 里跑 consumer.Run。测试结束时自动停掉。
func startConsumer(t *testing.T, group string, s *store.Store, hooks consumer.Hooks) *running {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	r := &running{cancel: cancel, done: make(chan error, 1)}
	cfg := consumer.Config{
		Brokers:        brokers,
		Group:          group,
		Topics:         []string{"order-events", "delivery-events"},
		SessionTimeout: 6 * time.Second, // broker 允许的最小值，崩溃后别的实例能更快接手
		Hooks:          hooks,
	}
	go func() { r.done <- consumer.Run(ctx, cfg, s, quietLog) }() // go 关键字：在新的 goroutine（轻量线程）里运行
	t.Cleanup(cancel)
	return r
}

// waitFor 每 200 毫秒检查一次 cond，直到它返回 true；超过 timeout 就让测试失败。
// 异步系统里不能"发完马上查"，消费者需要一点时间，所以要轮询等待。
func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("timed out after %v waiting for: %s", timeout, what)
}

// timelineLen 返回某个订单时间线的长度（查不了就当 0）。
func timelineLen(s *store.Store, orderID string) int {
	got, err := s.Timeline(context.Background(), orderID)
	if err != nil {
		return 0
	}
	return len(got)
}
