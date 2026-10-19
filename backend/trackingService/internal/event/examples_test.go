package event

// 契约测试：Go 读 backend/contracts/examples 里的 11 个样例，和 Java 的
// EventEnvelopeContractTest 读的是同一批文件。两边都绿，说明两种语言对格式的理解一致。
// 这一层是单元测试：只有 Go 代码和几个 JSON 文件，不需要 Docker。

import (
	"bytes"
	"encoding/json"
	"testing"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/examples"
)

// 下面 11 种 payload 的 Go 类型只在测试里用：tracking 的生产代码只读外壳，
// 这些类型存在的意义是证明"Go 读样例的方式和 Java 的 payload 类型一致"。
// 金额是 string（契约：钱一律用字符串）；可能为 null 的字段用指针（*int64），
// 因为 Go 的 int64 接到 null 会悄悄变成 0，指针才能区分"没有"和"0"。
type orderPlaced struct {
	UserID      *int64  `json:"userId"`
	ProductID   *int64  `json:"productId"`
	Quantity    *int    `json:"quantity"`
	TotalAmount *string `json:"totalAmount"`
}

type orderPaid struct {
	PaymentID     *int64   `json:"paymentId"`
	Amount        *string  `json:"amount"`
	BankTxnID     *string  `json:"bankTxnId"`
	CustomerEmail *string  `json:"customerEmail"`
	CustomerName  *string  `json:"customerName"`
	ToAddress     *string  `json:"toAddress"`
	FromAddresses []string `json:"fromAddresses"`
	ProductName   *string  `json:"productName"`
	Quantity      *int     `json:"quantity"`
}

type paymentFailed struct {
	PaymentID *int64  `json:"paymentId"`
	Attempt   *int    `json:"attempt"`
	Reason    *string `json:"reason"`
}

type orderCancelled struct {
	Reason *string `json:"reason"`
}

type orderRefunded struct {
	PaymentID   *int64  `json:"paymentId"`
	Amount      *string `json:"amount"`
	RefundTxnID *string `json:"refundTxnId"`
	Reason      *string `json:"reason"`
}

// 6 种配送事件共用一个 payload。deliveryId 是指针：Cancelled 样例里它是 null（ADR-09 取消标记）。
type deliveryStatus struct {
	DeliveryID *int64  `json:"deliveryId"`
	Status     *string `json:"status"`
}

// payloadType 按 eventType 返回一个新的、空的 payload 结构体指针，供 json 解码。
func payloadType(eventType string) any {
	switch eventType {
	case "OrderPlaced":
		return &orderPlaced{}
	case "OrderPaid":
		return &orderPaid{}
	case "PaymentFailed":
		return &paymentFailed{}
	case "OrderCancelled":
		return &orderCancelled{}
	case "OrderRefunded":
		return &orderRefunded{}
	default:
		return &deliveryStatus{}
	}
}

// decodeStrict 解码 payload，并且遇到结构体里没有的字段就报错。
// 用来发现"样例多了一个 Go 不知道的字段"这种漂移。
func decodeStrict(raw []byte, into any) error {
	dec := json.NewDecoder(bytes.NewReader(raw)) // 用 Decoder 而不是 json.Unmarshal，才能打开下一行的开关
	dec.DisallowUnknownFields()                  // 遇到未知字段就报错
	return dec.Decode(into)
}

// TestExamples_AllParse：11 个样例都能被 Parse 接受，外壳字段读对了，payload 能转成对应类型。
func TestExamples_AllParse(t *testing.T) {
	all := examples.All(t) // 读出 11 个样例文件
	if len(all) != 11 {    // 先确认文件数量，防止路径错了读到 0 个、测试"假绿"
		t.Fatalf("want 11 example files, got %d", len(all))
	}
	for _, ex := range all {
		// t.Run 开一个子测试，名字是文件名；一个样例失败不影响其他样例继续跑，报告里也能看到是哪个文件
		t.Run(ex.Name, func(t *testing.T) {
			env, err := Parse(ex.JSON) // 被测的函数
			if err != nil {
				t.Fatalf("Parse rejected a valid example: %v", err)
			}
			// 外壳字段要和文件名、样例内容对得上
			if env.EventType != ex.EventType {
				t.Errorf("eventType = %q, want %q", env.EventType, ex.EventType)
			}
			if env.OrderID != "42" { // 所有样例都是订单 "42"
				t.Errorf("orderId = %q, want \"42\"", env.OrderID)
			}
			if env.EventID == "" {
				t.Errorf("eventId is empty")
			}
			if env.OccurredAt.IsZero() { // IsZero：time.Time 的零值是公元 1 年，说明没读到
				t.Errorf("occurredAt not parsed")
			}
			// eventType 必须属于文件名里的那个 topic
			if topic, ok := TopicOf(env.EventType); !ok || topic != ex.Topic {
				t.Errorf("TopicOf(%q) = %q, %v; want %q", env.EventType, topic, ok, ex.Topic)
			}
			// payload 用严格模式转成对应的 Go 类型
			p := payloadType(ex.EventType)
			if err := decodeStrict(env.Payload, p); err != nil {
				t.Fatalf("payload does not match the Go type: %v", err)
			}
		})
	}
}

// TestExamples_PayloadValues：抽查几个最容易出错的值，确认 Go 和 Java 读出来的一样。
func TestExamples_PayloadValues(t *testing.T) {
	byName := map[string][]byte{} // 文件名 → 内容
	for _, ex := range examples.All(t) {
		byName[ex.Name] = ex.JSON
	}

	// 1) 金额是字符串 "259.80"，没有被读成浮点数 259.8
	env, err := Parse(byName["order-events-OrderPaid.json"])
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	var paid orderPaid
	if err := decodeStrict(env.Payload, &paid); err != nil {
		t.Fatalf("decode OrderPaid: %v", err)
	}
	if paid.Amount == nil || *paid.Amount != "259.80" {
		t.Errorf("OrderPaid.amount = %v, want \"259.80\"", paid.Amount)
	}

	// 2) Cancelled 的 deliveryId 是 null → 指针是 nil，而不是 0
	env, err = Parse(byName["delivery-events-Cancelled.json"])
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	var cancelled deliveryStatus
	if err := decodeStrict(env.Payload, &cancelled); err != nil {
		t.Fatalf("decode Cancelled: %v", err)
	}
	if cancelled.DeliveryID != nil {
		t.Errorf("Cancelled.deliveryId = %d, want null", *cancelled.DeliveryID)
	}

	// 3) occurredAt 读成了正确的 UTC 时刻
	env, err = Parse(byName["order-events-OrderPlaced.json"])
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	want := time.Date(2026, 10, 19, 2, 1, 0, 0, time.UTC)
	if !env.OccurredAt.Equal(want) { // Equal 比较的是时刻，不管时区写法
		t.Errorf("occurredAt = %v, want %v", env.OccurredAt, want)
	}
}
