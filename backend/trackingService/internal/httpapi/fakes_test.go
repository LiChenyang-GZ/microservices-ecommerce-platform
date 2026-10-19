package httpapi

// HTTP 测试用的替身：假的存储（fakeRepo）和假的 token 校验（fakeValidator）。
// 接口层的测试只关心"给什么请求、回什么响应"，不需要真数据库。

import (
	"context"
	"io"
	"log/slog"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/auth"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/store"
)

type fakeRepo struct {
	entries map[string][]store.Entry // orderId → 时间线
	owners  map[string]int64         // orderId → 下单用户
	err     error                    // 不是 nil 时，所有查询都返回这个错误（模拟数据库挂了）
}

func (f *fakeRepo) Timeline(ctx context.Context, orderID string) ([]store.Entry, error) {
	if f.err != nil {
		return nil, f.err
	}
	return f.entries[orderID], nil
}

func (f *fakeRepo) Owner(ctx context.Context, orderID string) (int64, bool, error) {
	if f.err != nil {
		return 0, false, f.err
	}
	id, ok := f.owners[orderID]
	return id, ok, nil
}

// fakeValidator：token 是 "user-7" 就当成用户 7，其他一律无效。
type fakeValidator struct{}

func (fakeValidator) Validate(ctx context.Context, token string) (auth.User, error) {
	if token == "user-7" {
		return auth.User{ID: 7, Email: "customer@example.com"}, nil
	}
	if token == "user-8" {
		return auth.User{ID: 8, Email: "other@example.com"}, nil
	}
	return auth.User{}, auth.ErrUnauthorized
}

var quietLog = slog.New(slog.NewTextHandler(io.Discard, nil))

var t0 = time.Date(2026, 10, 19, 2, 1, 0, 0, time.UTC)

// order42 是用户 7 的订单 42，有三条事件。
func order42() *fakeRepo {
	return &fakeRepo{
		entries: map[string][]store.Entry{"42": {
			{EventType: "OrderPlaced", Source: "order-events", OccurredAt: t0},
			{EventType: "OrderPaid", Source: "order-events", OccurredAt: t0.Add(time.Minute)},
			{EventType: "DeliveryCreated", Source: "delivery-events", OccurredAt: t0.Add(5 * time.Minute)},
		}},
		owners: map[string]int64{"42": 7},
	}
}
