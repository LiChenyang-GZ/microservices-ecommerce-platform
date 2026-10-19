//go:build integration

package store

// 存储层的失败场景（失败场景表 B 组），真 Postgres。

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
)

// #11 同一个 eventId 存两次 → 只有一行，而且保留第一次的内容。
// Kafka 只保证"至少投递一次"（规则 4），同一条消息被消费两次是正常情况，数据库必须挡住它。
func TestInsert_DuplicateEventId_StoredOnce(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	id := "3f1c2a6e-0000-4000-8000-000000000001"

	first := rec(id, "OrderPlaced", "42", t0, event.OrderEvents, 0)
	if err := s.Insert(ctx, first); err != nil {
		t.Fatalf("first insert: %v", err)
	}
	// 第二次：同一个 eventId（模拟重复投递）。为了能看出"保留了哪一次"，故意换了 offset
	again := rec(id, "OrderPlaced", "42", t0, event.OrderEvents, 99)
	if err := s.Insert(ctx, again); err != nil {
		// 重复不是错误：消费者拿到错误会重试，重复消息就会卡住整个分区
		t.Fatalf("duplicate insert must succeed quietly, got: %v", err)
	}

	if n := countRows(t, s); n != 1 {
		t.Fatalf("rows = %d, want 1", n)
	}
	var offset int64
	if err := s.pool.QueryRow(ctx, "SELECT kafka_offset FROM tracking_events").Scan(&offset); err != nil {
		t.Fatal(err)
	}
	if offset != 0 {
		t.Errorf("kept offset %d, want the first one (0)", offset)
	}
}

// #12 同一个 UUID，一次小写一次大写 → 还是同一个事件，只存一行。
// UUID 不区分大小写（Java 实测接受大写）；如果 event_id 存成普通文本，两个写法就会变成两行。
func TestInsert_SameUUIDDifferentCase_StoredOnce(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()

	if err := s.Insert(ctx, rec("3f1c2a6e-0000-4000-8000-0000000000ab", "OrderPlaced", "42", t0, event.OrderEvents, 0)); err != nil {
		t.Fatal(err)
	}
	if err := s.Insert(ctx, rec("3F1C2A6E-0000-4000-8000-0000000000AB", "OrderPlaced", "42", t0, event.OrderEvents, 0)); err != nil {
		t.Fatalf("duplicate insert must succeed quietly, got: %v", err)
	}
	if n := countRows(t, s); n != 1 {
		t.Fatalf("rows = %d, want 1 (same UUID written in two cases)", n)
	}
}

// #13 时间线排序：按 occurredAt 升序；时间相同时 order-events 在 delivery-events 前面（G4）；
// 再相同就按 offset。插入顺序故意打乱（模拟两个 topic 的消息到达顺序和发生顺序不同）。
func TestTimelineQuery_SortOrder(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	tie := t0.Add(10 * time.Minute) // 几条事件共用的时间

	inserts := []Record{ // 插入顺序 ≠ 期望顺序
		rec("00000000-0000-4000-8000-000000000001", "DeliveryCreated", "42", t0.Add(5*time.Minute), event.DeliveryEvents, 0),
		rec("00000000-0000-4000-8000-000000000002", "OrderPaid", "42", t0.Add(time.Minute), event.OrderEvents, 1),
		rec("00000000-0000-4000-8000-000000000003", "OrderPlaced", "42", t0, event.OrderEvents, 0),
		rec("00000000-0000-4000-8000-000000000004", "Lost", "42", tie, event.DeliveryEvents, 5),
		rec("00000000-0000-4000-8000-000000000005", "Received", "42", tie, event.DeliveryEvents, 4),
		rec("00000000-0000-4000-8000-000000000006", "OrderRefunded", "42", tie, event.OrderEvents, 2),
	}
	for _, r := range inserts {
		if err := s.Insert(ctx, r); err != nil {
			t.Fatal(err)
		}
	}

	got, err := s.Timeline(ctx, "42")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"OrderPlaced",     // 02:01
		"OrderPaid",       // 02:02
		"DeliveryCreated", // 02:06
		"OrderRefunded",   // 02:11，同一时间里 order-events 排最前
		"Received",        // 02:11，delivery-events，offset 4
		"Lost",            // 02:11，delivery-events，offset 5
	}
	if len(got) != len(want) {
		t.Fatalf("got %d entries, want %d", len(got), len(want))
	}
	for i := range want {
		if got[i].EventType != want[i] {
			var order []string
			for _, e := range got {
				order = append(order, e.EventType)
			}
			t.Fatalf("timeline order = %v\n                  want %v", order, want)
		}
	}
}

// #14 时间线里的 occurredAt 是 UTC。
// pgx 读 timestamptz 时会把时间转成"本机时区"。Christy 的电脑在悉尼（10 月是 +11:00），
// 直接格式化就会得到 "2026-10-19T13:01:00+11:00"：时刻没错，但和事件里写的 "…02:01:00Z" 对不上。
// 为了在任何电脑（包括时区是 UTC 的 CI）上结果都一样，测试里把本机时区临时设成悉尼。
func TestTimeline_OccurredAtIsUTC(t *testing.T) {
	saved := time.Local
	time.Local = time.FixedZone("AEDT", 11*60*60) // 假装本机在悉尼夏令时
	t.Cleanup(func() { time.Local = saved })      // 测试结束改回来

	s := newStore(t)
	ctx := context.Background()
	if err := s.Insert(ctx, rec("3f1c2a6e-0000-4000-8000-000000000001", "OrderPlaced", "42", t0, event.OrderEvents, 0)); err != nil {
		t.Fatal(err)
	}
	got, err := s.Timeline(ctx, "42")
	if err != nil || len(got) != 1 {
		t.Fatalf("timeline = %v, %v", got, err)
	}
	if s := got[0].OccurredAt.Format(time.RFC3339); s != "2026-10-19T02:01:00Z" {
		t.Errorf("occurredAt = %s, want 2026-10-19T02:01:00Z", s)
	}
}

// payload 原样存下来（jsonb），以后要显示金额等细节时不用重放就有数据。安全网。
func TestInsert_KeepsPayload(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	r := rec("3f1c2a6e-0000-4000-8000-000000000002", "OrderPaid", "42", t0, event.OrderEvents, 0)
	r.Payload = json.RawMessage(`{"amount":"259.80"}`)
	if err := s.Insert(ctx, r); err != nil {
		t.Fatal(err)
	}
	var amount string
	if err := s.pool.QueryRow(ctx, "SELECT payload->>'amount' FROM tracking_events").Scan(&amount); err != nil {
		t.Fatal(err)
	}
	if amount != "259.80" {
		t.Errorf("amount = %q", amount)
	}
}
