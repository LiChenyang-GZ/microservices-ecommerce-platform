package httpapi

// 查询接口的 HTTP 测试。httptest.NewRecorder 是一个假的 ResponseWriter：
// 不开端口、不走网络，直接把我们的 handler 当函数调用，然后检查它写了什么状态码和响应体。

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// get 发一个 GET 请求给 handler；token 为空就不带 Authorization 头。
func get(h http.Handler, path, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil) // 造一个请求对象，不会真的发出去
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder() // 记录响应
	h.ServeHTTP(rec, req)         // 直接调用 handler
	return rec
}

// TestTimeline_HappyPath：用户 7 查自己的订单 42 → 200，JSON 数组，字段名和设计一致。
func TestTimeline_HappyPath(t *testing.T) {
	h := New(order42(), fakeValidator{}, quietLog)

	rec := get(h, "/api/tracking/orders/42/timeline", "user-7")

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", rec.Code, rec.Body)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q", ct)
	}
	var got []Entry
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("body is not a JSON array of entries: %v; body = %s", err, rec.Body)
	}
	if len(got) != 3 {
		t.Fatalf("got %d entries, want 3", len(got))
	}
	// 设计：[{ eventType, source, occurredAt }, ...]
	want := Entry{EventType: "OrderPlaced", Source: "order-events", OccurredAt: "2026-10-19T02:01:00Z"}
	if got[0] != want {
		t.Errorf("first entry = %+v, want %+v", got[0], want)
	}
}
