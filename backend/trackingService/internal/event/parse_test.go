package event

// 解析外壳的单元测试（失败场景表 A 组）。
// 目标：Go 对"什么是坏消息"的判断和 Java 的 EventCodec 一致（契约 §2）。
// 坏消息 → Parse 返回的错误能被 errors.Is(err, ErrInvalid) 认出来 → 消费者把它送进 DLT。

import (
	"encoding/json"
	"errors"
	"testing"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/examples"
)

// validFields 返回一个合法外壳的各个字段（JSON 片段），测试在它的基础上改一个字段。
// 用 map[string]json.RawMessage：值是原样的 JSON 文本，这样可以放进 null、数字、数组这些"错误类型"。
func validFields() map[string]json.RawMessage {
	return map[string]json.RawMessage{
		"eventId":    json.RawMessage(`"3f1c2a6e-0000-4000-8000-000000000001"`),
		"eventType":  json.RawMessage(`"OrderPlaced"`),
		"orderId":    json.RawMessage(`"42"`),
		"occurredAt": json.RawMessage(`"2026-10-19T02:01:00Z"`),
		"payload":    json.RawMessage(`{"userId":7,"productId":3,"quantity":2,"totalAmount":"259.80"}`),
	}
}

// build 把字段拼成一条完整的 JSON 消息。
func build(t *testing.T, fields map[string]json.RawMessage) []byte {
	t.Helper()
	b, err := json.Marshal(fields)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// mustReject 断言 Parse 把这条消息判为坏消息。
func mustReject(t *testing.T, data []byte) {
	t.Helper()
	env, err := Parse(data)
	if err == nil {
		// 把解析出来的结果打印出来：能看到"缺的字段变成了零值"
		t.Fatalf("Parse accepted a bad message: %s\n  parsed as %+v", data, env)
	}
	if !errors.Is(err, ErrInvalid) { // 必须是 ErrInvalid，消费者才知道"送 DLT，不要重试"
		t.Fatalf("error %v does not wrap ErrInvalid", err)
	}
}

// mustAccept 断言 Parse 接受这条消息。
func mustAccept(t *testing.T, data []byte) Envelope {
	t.Helper()
	env, err := Parse(data)
	if err != nil {
		t.Fatalf("Parse rejected a valid message %s: %v", data, err)
	}
	return env
}

var envelopeFields = []string{"eventId", "eventType", "orderId", "occurredAt", "payload"}

// #3 外壳缺字段 → 坏消息。
// Go 的坑：encoding/json 遇到缺的字段不报错，直接给零值（空字符串、公元 1 年、nil）。
func TestParse_MissingField_Rejected(t *testing.T) {
	for _, field := range envelopeFields {
		t.Run(field, func(t *testing.T) { // 5 个子测试，每次删掉一个字段
			f := validFields()
			delete(f, field)
			mustReject(t, build(t, f))
		})
	}
}

// #4 外壳字段是 null → 坏消息。null 在 Go 里同样被悄悄当成零值。
func TestParse_NullField_Rejected(t *testing.T) {
	for _, field := range envelopeFields {
		t.Run(field, func(t *testing.T) {
			f := validFields()
			f[field] = json.RawMessage(`null`)
			mustReject(t, build(t, f))
		})
	}
}

// #5 orderId 是数字 42 不是字符串 "42" → 坏消息（契约：orderId 是字符串）。
// Java 要专门配置 Jackson 才会拒绝；Go 的 string 字段本来就不收数字，所以这个测试是安全网。
func TestParse_OrderIdAsNumber_Rejected(t *testing.T) {
	f := validFields()
	f["orderId"] = json.RawMessage(`42`)
	mustReject(t, build(t, f))
}

// #6 payload 不是 JSON 对象 → 坏消息。
func TestParse_PayloadNotObject_Rejected(t *testing.T) {
	cases := map[string]string{ // 子测试名 → payload 的值
		"array":  `[1,2]`,
		"string": `"hello"`,
		"number": `5`,
		"bool":   `true`,
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			f := validFields()
			f["payload"] = json.RawMessage(raw)
			mustReject(t, build(t, f))
		})
	}
}

// #7 eventId 不是 UUID → 坏消息（Java 用 java.util.UUID 接，实测会拒绝 "abc"）。
// 大写的 UUID 是合法的（Java 实测接受），不能误杀。
func TestParse_EventIdNotUUID_Rejected(t *testing.T) {
	for _, bad := range []string{`"abc"`, `""`, `"3f1c2a6e-0000-4000-8000"`} {
		t.Run(bad, func(t *testing.T) {
			f := validFields()
			f["eventId"] = json.RawMessage(bad)
			mustReject(t, build(t, f))
		})
	}
	t.Run("uppercase is valid", func(t *testing.T) {
		f := validFields()
		f["eventId"] = json.RawMessage(`"3F1C2A6E-0000-4000-8000-000000000001"`)
		mustAccept(t, build(t, f))
	})
}

// #8 orderId / eventType 是空白字符串 → 坏消息（Java 的 requireText 用 isBlank 检查）。
func TestParse_BlankText_Rejected(t *testing.T) {
	for _, field := range []string{"orderId", "eventType"} {
		for _, blank := range []string{`""`, `"   "`} {
			t.Run(field+"="+blank, func(t *testing.T) {
				f := validFields()
				f[field] = json.RawMessage(blank)
				mustReject(t, build(t, f))
			})
		}
	}
}

// #9 occurredAt 的格式和 Java 的 Instant 一致：
// 没有时区 → 拒绝；带毫秒、带 +10:00 → 接受（都是实测 Java 的结果）。
// Go 的 time.Time 按 RFC 3339 解析，本来就是这个行为，所以是安全网。
func TestParse_OccurredAt_MatchesJava(t *testing.T) {
	for _, bad := range []string{`"2026-10-19T02:01:00"`, `"not a date"`, `1760839260`} {
		t.Run("reject "+bad, func(t *testing.T) {
			f := validFields()
			f["occurredAt"] = json.RawMessage(bad)
			mustReject(t, build(t, f))
		})
	}
	for _, good := range []string{`"2026-10-19T02:01:00.123Z"`, `"2026-10-19T12:01:00+10:00"`} {
		t.Run("accept "+good, func(t *testing.T) {
			f := validFields()
			f["occurredAt"] = json.RawMessage(good)
			mustAccept(t, build(t, f))
		})
	}
}

// #10 外壳多一个字段 → 接受，忽略它（契约 README §7-5：以后只加字段）。
// 注意：Java 现在在这里会拒绝（G1），协调者会单独修。Go 默认就忽略未知字段，所以是安全网。
func TestParse_ExtraEnvelopeField_Ignored(t *testing.T) {
	f := validFields()
	f["traceId"] = json.RawMessage(`"abc-123"`)
	env := mustAccept(t, build(t, f))
	if env.OrderID != "42" {
		t.Errorf("orderId = %q", env.OrderID)
	}
}

// 不是 JSON、空消息 → 坏消息。
func TestParse_NotJSON_Rejected(t *testing.T) {
	for name, data := range map[string]string{"garbage": "not json", "empty": "", "truncated": `{"eventId":`} {
		t.Run(name, func(t *testing.T) { mustReject(t, []byte(data)) })
	}
}

// #2 每个已知 eventType 都有样例，样例里也没有 Go 不认识的类型（对应 Java 的 examples_coverEveryEventType）。
func TestExamples_CoverEveryKnownEventType(t *testing.T) {
	have := map[string]bool{} // 样例里出现过的类型
	for _, ex := range examples.All(t) {
		have[ex.EventType] = true
		if _, ok := TopicOf(ex.EventType); !ok {
			t.Errorf("example %s has an event type Go does not know", ex.Name)
		}
	}
	for _, typ := range KnownTypes() {
		if !have[typ] {
			t.Errorf("no example file for %s", typ)
		}
	}
}
