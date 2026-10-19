package httpapi

// 查询接口的失败场景（失败场景表 E 组 + #15）。
// 前几个测试用真的 auth.Client 去调一个假的 store（httptest.NewServer），
// 这样"store 挂了 / 很慢 / 说 token 无效"这些情况都是真的 HTTP 行为。

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/auth"
)

// fakeStore 起一个假的 store：每收到一个请求 hits 加一，然后交给 reply 决定怎么回。
func fakeStore(t *testing.T, reply http.HandlerFunc) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var hits atomic.Int32 // atomic：多个 goroutine 同时加也不会乱
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		reply(w, r)
	}))
	t.Cleanup(srv.Close)
	return srv, &hits
}

// storeSaysValid：假 store 回答"token 有效，是用户 7"。
func storeSaysValid(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"valid":true,"userId":7,"email":"customer@example.com"}`))
}

// #29 没带 token → 401，而且根本不去问 store（省一次网络调用）。安全网。
func TestTimeline_NoToken_Returns401(t *testing.T) {
	srv, hits := fakeStore(t, storeSaysValid)
	h := New(order42(), &auth.Client{BaseURL: srv.URL}, quietLog)

	rec := get(h, "/api/tracking/orders/42/timeline", "") // 不带 Authorization 头

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
	if hits.Load() != 0 {
		t.Errorf("store was called %d times for a request with no token", hits.Load())
	}
}

// #30 store 说 token 无效（401）→ 我们也回 401。安全网。
func TestTimeline_InvalidToken_Returns401(t *testing.T) {
	srv, _ := fakeStore(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized) // store 的 validate-token 对无效 token 回 401
		_, _ = w.Write([]byte(`{"valid":false,"message":"Token is invalid or expired"}`))
	})
	h := New(order42(), &auth.Client{BaseURL: srv.URL}, quietLog)

	if rec := get(h, "/api/tracking/orders/42/timeline", "expired"); rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

// #31 store 挂了或者出错（5xx）→ 503，不是 401（G7）。
// 401 会让前端以为登录过期、把用户踢出去；503 的意思是"稍后再试"，这才是真实情况。
func TestStoreDown_Returns503(t *testing.T) {
	t.Run("store unreachable", func(t *testing.T) {
		srv, _ := fakeStore(t, storeSaysValid)
		url := srv.URL
		srv.Close() // 关掉假 store：之后连它会被拒绝，和 store 宕机一样
		h := New(order42(), &auth.Client{BaseURL: url}, quietLog)

		if rec := get(h, "/api/tracking/orders/42/timeline", "user-7"); rec.Code != http.StatusServiceUnavailable {
			t.Errorf("status = %d, want 503", rec.Code)
		}
	})
	t.Run("store returns 500", func(t *testing.T) {
		srv, _ := fakeStore(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusInternalServerError)
		})
		h := New(order42(), &auth.Client{BaseURL: srv.URL}, quietLog)

		if rec := get(h, "/api/tracking/orders/42/timeline", "user-7"); rec.Code != http.StatusServiceUnavailable {
			t.Errorf("status = %d, want 503", rec.Code)
		}
	})
}

// #32 store 很慢（3 秒才回）→ 我们最多等 2 秒，然后回 503，不能让请求一直挂着。
// Go 的 http.DefaultClient 默认没有超时，忘了设就会一直等。
func TestStoreSlow_TimesOutWith503(t *testing.T) {
	srv, _ := fakeStore(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(3 * time.Second): // 3 秒后才回答
			storeSaysValid(w, r)
		case <-r.Context().Done(): // 调用方放弃了就提前结束，测试不用白等
		}
	})
	h := New(order42(), &auth.Client{BaseURL: srv.URL}, quietLog) // 不传 HTTP：用默认的客户端

	start := time.Now()
	rec := get(h, "/api/tracking/orders/42/timeline", "user-7")
	elapsed := time.Since(start)

	if elapsed > 2500*time.Millisecond {
		t.Errorf("request took %v; the store call must give up after about 2s", elapsed.Round(time.Millisecond))
	}
	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", rec.Code)
	}
}

// #33 用户 8 查用户 7 的订单 → 404（G5 / Q5）：不告诉他这个订单存在不存在。
func TestTimeline_OtherUsersOrder_Returns404(t *testing.T) {
	h := New(order42(), fakeValidator{}, quietLog)

	if rec := get(h, "/api/tracking/orders/42/timeline", "user-8"); rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404; body = %s", rec.Code, rec.Body)
	}
}

// #15 不存在的订单（或者 OrderPlaced 还没到）→ 404（Q6）。
// 和 #33 回得一模一样，别人就没法靠试订单号来知道哪些订单存在。
func TestTimeline_UnknownOrder_Returns404(t *testing.T) {
	h := New(order42(), fakeValidator{}, quietLog)

	if rec := get(h, "/api/tracking/orders/999/timeline", "user-7"); rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404; body = %s", rec.Code, rec.Body)
	}
}

// #34 查询时数据库挂了 → 503，响应里不带数据库的错误原文（不把内部细节暴露给用户）。
func TestTimeline_DbDown_Returns503(t *testing.T) {
	repo := order42()
	repo.err = errDB
	h := New(repo, fakeValidator{}, quietLog)

	rec := get(h, "/api/tracking/orders/42/timeline", "user-7")

	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503", rec.Code)
	}
	if strings.Contains(rec.Body.String(), "10.0.0.5") {
		t.Errorf("response leaks the database error: %s", rec.Body)
	}
}

// errDB 模拟 pgx 连不上数据库时的错误，里面有内部地址，不能出现在响应里。
var errDB = &dbError{"failed to connect to `host=10.0.0.5 user=tracking database=tracking_db`: connection refused"}

type dbError struct{ msg string }

func (e *dbError) Error() string { return e.msg }
