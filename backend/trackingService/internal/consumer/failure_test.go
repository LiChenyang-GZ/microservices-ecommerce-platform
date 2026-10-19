package consumer

// Handler 的失败场景（失败场景表 C 组里能用假依赖测的那几行）。
// 规则 6 / ADR-08：失败分两种——
//   消息本身坏了（永远不会成功）→ 送 DLT，然后继续；
//   暂时性失败（数据库连不上）  → 原地退避重试，不能跳过，也不能送 DLT。
// Handle 返回 nil 的意思是"这条处理完了，可以提交 offset"。

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
)

// #18（单元版）外壳缺字段的坏消息 → 进 DLT，不存库，Handle 返回 nil（继续处理下一条）。
func TestPoisonEnvelope_GoesToDlt(t *testing.T) {
	sink, dlt := &fakeSink{}, &fakeDLT{}
	h := newHandler(sink, dlt)
	// 缺 orderId 的消息
	m := Message{Topic: event.OrderEvents, Offset: 3, Value: []byte(
		`{"eventId":"3f1c2a6e-0000-4000-8000-000000000001","eventType":"OrderPlaced","occurredAt":"2026-10-19T02:01:00Z","payload":{}}`)}

	if err := h.Handle(context.Background(), m); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	assertDeadLettered(t, sink, dlt, m)
}

// #19 消息体不是 JSON、或者是空的 → 进 DLT。
func TestNotJson_GoesToDlt(t *testing.T) {
	for name, value := range map[string]string{"garbage": "not json at all", "empty": ""} {
		t.Run(name, func(t *testing.T) {
			sink, dlt := &fakeSink{}, &fakeDLT{}
			h := newHandler(sink, dlt)
			m := Message{Topic: event.DeliveryEvents, Offset: 9, Value: []byte(value)}

			if err := h.Handle(context.Background(), m); err != nil {
				t.Fatalf("Handle: %v", err)
			}
			assertDeadLettered(t, sink, dlt, m)
		})
	}
}

// #20 不认识的 eventType（比如以后新加的事件）→ 跳过：不存库、不进 DLT，Handle 返回 nil。
// 契约：未知类型不是坏消息。以后升级了代码，可以靠重放把它们补回来。
func TestUnknownEventType_SkippedNotDlt(t *testing.T) {
	sink, dlt := &fakeSink{}, &fakeDLT{}
	h := newHandler(sink, dlt)
	m := Message{Topic: event.OrderEvents, Offset: 4, Value: []byte(
		`{"eventId":"3f1c2a6e-0000-4000-8000-000000000001","eventType":"OrderShipped","orderId":"42","occurredAt":"2026-10-19T02:01:00Z","payload":{}}`)}

	if err := h.Handle(context.Background(), m); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	if _, stored := sink.snapshot(); len(stored) != 0 {
		t.Errorf("stored an unknown event type: %+v", stored)
	}
	if calls, _ := dlt.snapshot(); calls != 0 {
		t.Errorf("unknown event type went to the DLT (%d calls); it should be skipped", calls)
	}
}

// #21 事件出现在错的 topic（OrderPaid 出现在 delivery-events 上）→ 当坏消息送 DLT。
// 时间线的 source 来自 topic，放错了就会显示错，而且说明生产者有 bug，需要人看。
func TestEventOnWrongTopic_GoesToDlt(t *testing.T) {
	sink, dlt := &fakeSink{}, &fakeDLT{}
	h := newHandler(sink, dlt)
	m := exampleMsg(t, "order-events-OrderPaid.json")
	m.Topic = event.DeliveryEvents // 放到错的 topic

	if err := h.Handle(context.Background(), m); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	assertDeadLettered(t, sink, dlt, m)
}

// #22 数据库暂时连不上（前 3 次失败，第 4 次好了）→ 原地重试直到成功；不跳过、不进 DLT。
func TestTransientDbFailure_RetriesInPlace(t *testing.T) {
	sink, dlt := &fakeSink{failFirst: 3}, &fakeDLT{}
	h := newHandler(sink, dlt)
	m := exampleMsg(t, "order-events-OrderPlaced.json")

	if err := h.Handle(context.Background(), m); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	calls, stored := sink.snapshot()
	if calls != 4 { // 3 次失败 + 1 次成功
		t.Errorf("Insert called %d times, want 4 (retry until the database is back)", calls)
	}
	if len(stored) != 1 {
		t.Errorf("event stored %d times, want 1: a transient failure must not lose the event", len(stored))
	}
	if dc, _ := dlt.snapshot(); dc != 0 {
		t.Errorf("a database outage sent the event to the DLT (%d calls)", dc)
	}
}

// #23 坏消息要进 DLT，但 DLT 暂时写不进去（前 2 次失败）→ 重试 DLT，直到写进去才返回。
// 在这之前 Handle 不返回，offset 就不会被提交，这条坏消息不会悄悄消失（Q10）。
func TestDltPublishFails_NotCommitted(t *testing.T) {
	sink, dlt := &fakeSink{}, &fakeDLT{failFirst: 2}
	h := newHandler(sink, dlt)
	m := Message{Topic: event.OrderEvents, Offset: 5, Value: []byte("not json")}

	if err := h.Handle(context.Background(), m); err != nil {
		t.Fatalf("Handle: %v", err)
	}
	calls, published := dlt.snapshot()
	if calls != 3 {
		t.Errorf("DLT Publish called %d times, want 3 (2 failures + 1 success)", calls)
	}
	if len(published) != 1 {
		t.Errorf("message reached the DLT %d times, want 1", len(published))
	}
}

// #28 正在退避重试时收到停止信号（ctx 被取消）→ Handle 马上返回 ctx 的错误，不假装处理完了。
// 用 time.Sleep 退避的话要等睡完才能退出；要用能被 ctx 打断的等待。
func TestShutdownDuringRetry_ExitsPromptly(t *testing.T) {
	sink, dlt := &fakeSink{failFirst: 1 << 30}, &fakeDLT{} // 数据库一直连不上
	h := newHandler(sink, dlt)
	h.RetryMin, h.RetryMax = 200*time.Millisecond, 10*time.Second // 退避时间长一点，才看得出能不能被打断
	m := exampleMsg(t, "order-events-OrderPlaced.json")

	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(500*time.Millisecond, cancel) // 半秒后"收到 SIGTERM"

	start := time.Now()
	err := h.Handle(ctx, m)
	elapsed := time.Since(start)

	if calls, _ := sink.snapshot(); calls < 2 {
		t.Errorf("Insert called %d times; the handler must keep retrying while the database is down", calls)
	}
	if !errors.Is(err, context.Canceled) {
		t.Errorf("Handle returned %v, want context.Canceled (the message is NOT done, its offset must not be committed)", err)
	}
	if elapsed > 1500*time.Millisecond {
		t.Errorf("Handle took %v to notice the shutdown, want < 1.5s", elapsed)
	}
}

// assertDeadLettered：消息进了 DLT（原样、带着坏消息的原因），没有存库。
func assertDeadLettered(t *testing.T, sink *fakeSink, dlt *fakeDLT, m Message) {
	t.Helper()
	if _, stored := sink.snapshot(); len(stored) != 0 {
		t.Errorf("bad message was stored: %+v", stored)
	}
	_, published := dlt.snapshot()
	if len(published) != 1 {
		t.Fatalf("DLT got %d messages, want 1", len(published))
	}
	if string(published[0].Value) != string(m.Value) || published[0].Topic != m.Topic || published[0].Offset != m.Offset {
		t.Errorf("DLT got %+v, want the original message %+v", published[0], m)
	}
	dlt.mu.Lock()
	reason := dlt.reasons[0]
	dlt.mu.Unlock()
	if reason == nil {
		t.Errorf("DLT got no reason")
	}
}
