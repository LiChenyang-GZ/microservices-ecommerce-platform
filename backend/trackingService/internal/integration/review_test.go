//go:build integration

package integration

// 审查补充的失败场景（失败场景表 #40，来自《07-第1波审查-C-Go-tracking》的高危发现）。
//
// 文件名故意以 r 开头：Go 按文件名顺序跑测试，这个文件排在最后。
// 修好之前，这里发进 order-events 的坏消息会让"从头读"的消费者卡住；
// 放在最后跑，就不会连累同一个包里其他测试（它们都会新建消费者组、从最早的消息读起）。

import (
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/consumer"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
)

// #40 消息能通过 Parse（外壳完全合法），但 Postgres 永远不会接受它。
// 这种消息重试一万次也存不进去，按规则 6 / ADR-08 它是"坏消息"：应该进 DLT，然后继续处理后面的消息。
// 如果把它当成"数据库暂时不可用"无限重试，这个消费者就永远卡在这一条上，后面的事件全部存不进去。
func TestPermanentDbRejection_GoesToDlt_NextEventStored(t *testing.T) {
	cases := []struct {
		name    string
		orderID func(real string) string // 外壳里的 orderId（JSON 字面量）
		payload string                   // 外壳里的 payload（原样的 JSON 文本）
	}{
		{
			// JSON 里的 \u0000 是合法 JSON，Go 也接受；但 Postgres 的 jsonb 不能存 NUL（SQLSTATE 22P05）
			name:    "payload contains \\u0000",
			orderID: func(real string) string { return `"` + real + `"` },
			payload: `{"note":"\u0000"}`,
		},
		{
			// orderId 字符串里带 NUL：Go 的 string 可以装 NUL，Postgres 的 text 列不行（SQLSTATE 22021）
			name:    "orderId contains NUL",
			orderID: func(real string) string { return `"` + real + `\u0000x"` },
			payload: `{}`,
		},
		{
			// payload 里有一个非法的 UTF-8 字节 0xFF：Go 的 json.RawMessage 原样保留，Postgres 拒绝（SQLSTATE 22021）
			name:    "payload contains invalid UTF-8",
			orderID: func(real string) string { return `"` + real + `"` },
			payload: "{\"note\":\"\xff\"}",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := openStore(t)
			orderID := newOrderID()
			group := "permanent-reject-" + orderID

			// 1) 先发坏消息。Kafka key 用正常的 orderID，保证它和下面那条正常事件在同一个分区、排在前面
			bad := []byte(fmt.Sprintf(
				`{"eventId":"%s","eventType":"OrderPaid","orderId":%s,"occurredAt":"2026-10-19T02:02:00Z","payload":%s}`,
				uuid.NewString(), tc.orderID(orderID), tc.payload))
			if _, err := event.Parse(bad); err != nil {
				// 前提检查：这个场景说的是"能过 Parse 的消息"。如果 Parse 已经拒绝了，场景就不成立
				t.Fatalf("precondition: Parse should accept this message, got %v", err)
			}
			produce(t, event.OrderEvents, orderID, bad)

			// 2) 再发一条正常的 OrderPlaced（同一个 key → 同一个分区 → 排在坏消息后面）
			ex := exampleNamed(t, examplesFor(t, orderID), "order-events-OrderPlaced.json")
			produce(t, ex.Topic, orderID, ex.JSON)

			startConsumer(t, group, s, consumer.Hooks{})

			// 3) 断言一：后面那条正常事件在 30 秒内存进库（说明消费者没有卡在坏消息上）
			waitFor(t, 30*time.Second, "the valid event after the permanently rejected one to be stored (consumer is stuck retrying)",
				func() bool { return rowsFor(t, orderID) == 1 })

			// 4) 断言二：坏消息原样进了 order-events.DLT，带着失败原因
			dead := findDLT(t, event.OrderEvents+".DLT", orderID)
			if string(dead.Value) != string(bad) {
				t.Errorf("DLT value = %q, want the original bytes %q", dead.Value, bad)
			}
			if len(headers(dead)["kafka_dlt-exception-message"]) == 0 {
				t.Errorf("kafka_dlt-exception-message is empty; the DLT must say why")
			}
		})
	}
}
