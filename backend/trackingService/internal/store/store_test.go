//go:build integration

package store

// 存储层的集成测试：真的 Postgres（见 main_test.go），不用假数据库。
// 为什么不用内存数据库：ON CONFLICT、uuid 类型、timestamptz 时区这些行为只有真 Postgres 才准（八股 G4）。

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
)

// rec 造一条测试用的记录，只填测试关心的字段。
func rec(eventID, eventType, orderID string, at time.Time, topic string, offset int64) Record {
	return Record{
		Envelope: event.Envelope{
			EventID:    eventID,
			EventType:  eventType,
			OrderID:    orderID,
			OccurredAt: at,
			Payload:    json.RawMessage(`{}`),
		},
		Topic:     topic,
		Partition: 0,
		Offset:    offset,
	}
}

// t0 是测试里用的基准时间：2026-10-19 02:01:00 UTC，和样例一致。
var t0 = time.Date(2026, 10, 19, 2, 1, 0, 0, time.UTC)

// TestInsertAndTimeline_HappyPath：存进去两条事件，按订单查得出来，字段都对。
func TestInsertAndTimeline_HappyPath(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()

	// 订单 42 的两条事件，按时间顺序插入
	if err := s.Insert(ctx, rec("3f1c2a6e-0000-4000-8000-000000000001", "OrderPlaced", "42", t0, event.OrderEvents, 0)); err != nil {
		t.Fatalf("insert: %v", err)
	}
	if err := s.Insert(ctx, rec("3f1c2a6e-0000-4000-8000-000000000002", "OrderPaid", "42", t0.Add(time.Minute), event.OrderEvents, 1)); err != nil {
		t.Fatalf("insert: %v", err)
	}
	// 另一个订单的事件，不应该出现在 42 的时间线里
	if err := s.Insert(ctx, rec("3f1c2a6e-0000-4000-8000-000000000099", "OrderPlaced", "43", t0, event.OrderEvents, 2)); err != nil {
		t.Fatalf("insert: %v", err)
	}

	got, err := s.Timeline(ctx, "42") // 被测：查订单 42
	if err != nil {
		t.Fatalf("timeline: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d entries, want 2: %+v", len(got), got)
	}
	if got[0].EventType != "OrderPlaced" || got[1].EventType != "OrderPaid" {
		t.Errorf("event types = %q, %q", got[0].EventType, got[1].EventType)
	}
	if got[0].Source != event.OrderEvents {
		t.Errorf("source = %q, want %q", got[0].Source, event.OrderEvents)
	}
	if !got[0].OccurredAt.Equal(t0) { // Equal 比时刻，不比时区写法（时区在别的测试里查）
		t.Errorf("occurredAt = %v, want %v", got[0].OccurredAt, t0)
	}
}

// TestOwner_ReadsUserIdFromOrderPlaced：从 OrderPlaced 的 payload 里读出下单用户（用来做 Q5 的归属检查）。
func TestOwner_ReadsUserIdFromOrderPlaced(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()

	r := rec("3f1c2a6e-0000-4000-8000-000000000001", "OrderPlaced", "42", t0, event.OrderEvents, 0)
	r.Payload = json.RawMessage(`{"userId":7,"productId":3,"quantity":2,"totalAmount":"259.80"}`) // 和样例一样
	if err := s.Insert(ctx, r); err != nil {
		t.Fatalf("insert: %v", err)
	}

	userID, found, err := s.Owner(ctx, "42")
	if err != nil {
		t.Fatalf("owner: %v", err)
	}
	if !found || userID != 7 {
		t.Errorf("Owner(42) = %d, %v; want 7, true", userID, found)
	}

	// OrderPlaced 还没到的订单：found = false
	if _, found, _ := s.Owner(ctx, "999"); found {
		t.Errorf("Owner(999) found = true, want false")
	}
}
