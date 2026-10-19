package consumer

// Handler 的单元测试：真的 Handler，假的数据库（fakeSink）和假的 DLT（fakeDLT）。
// 测的是"一条消息来了，该存、该跳过、还是该进 DLT"这个判断。

import (
	"context"
	"testing"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/examples"
)

// newHandler 组装一个用假依赖的 Handler。退避时间设得很短，测试不用真等。
func newHandler(sink *fakeSink, dlt *fakeDLT) *Handler {
	return &Handler{
		Sink:     sink,
		DLT:      dlt,
		Log:      quietLog,
		RetryMin: time.Millisecond,
		RetryMax: 5 * time.Millisecond,
	}
}

// exampleMsg 把一个样例文件包装成一条 Kafka 消息。
func exampleMsg(t *testing.T, name string) Message {
	t.Helper()
	for _, ex := range examples.All(t) {
		if ex.Name == name {
			return Message{Topic: ex.Topic, Partition: 0, Offset: 7, Key: []byte("42"), Value: ex.JSON}
		}
	}
	t.Fatalf("no example %s", name)
	return Message{}
}

// TestHandle_ValidEvent_Stored：正常的事件 → 存进数据库，带上 topic 和 offset，不进 DLT。
func TestHandle_ValidEvent_Stored(t *testing.T) {
	sink, dlt := &fakeSink{}, &fakeDLT{}
	h := newHandler(sink, dlt)
	m := exampleMsg(t, "order-events-OrderPaid.json")

	if err := h.Handle(context.Background(), m); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	_, stored := sink.snapshot()
	if len(stored) != 1 {
		t.Fatalf("stored %d records, want 1", len(stored))
	}
	got := stored[0]
	if got.EventType != "OrderPaid" || got.OrderID != "42" {
		t.Errorf("stored %s for order %s", got.EventType, got.OrderID)
	}
	// 时间线排序要用到 topic 和 offset，所以它们也必须存下来
	if got.Topic != event.OrderEvents || got.Offset != 7 {
		t.Errorf("topic/offset = %s/%d, want %s/7", got.Topic, got.Offset, event.OrderEvents)
	}
	if calls, _ := dlt.snapshot(); calls != 0 {
		t.Errorf("DLT called %d times for a valid event", calls)
	}
}
