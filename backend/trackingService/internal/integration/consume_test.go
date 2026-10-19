//go:build integration

package integration

import (
	"context"
	"sort"
	"testing"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/consumer"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
)

// TestConsume_ExamplesEndToEnd：把 11 个样例发进真 Kafka → tracking 消费 → 库里能查到这个订单的 11 条事件。
// 这是整条链路的正常流程：Kafka → 消费者 → Postgres → 查询。
func TestConsume_ExamplesEndToEnd(t *testing.T) {
	s := openStore(t)
	orderID := newOrderID()

	for _, ex := range examplesFor(t, orderID) {
		produce(t, ex.Topic, orderID, ex.JSON) // 每个样例发到它自己的 topic
	}

	startConsumer(t, "e2e-"+orderID, s, consumer.Hooks{}) // 新的消费者组，从最早的消息开始读

	waitFor(t, 30*time.Second, "11 events stored", func() bool { return timelineLen(s, orderID) == 11 })

	// 11 种事件都在，而且每种只有一条
	got, err := s.Timeline(context.Background(), orderID)
	if err != nil {
		t.Fatal(err)
	}
	var types []string
	for _, e := range got {
		types = append(types, e.EventType)
	}
	sort.Strings(types)
	want := event.KnownTypes() // 已经排好序
	for i := range want {
		if types[i] != want[i] {
			t.Fatalf("event types = %v, want %v", types, want)
		}
	}
}
