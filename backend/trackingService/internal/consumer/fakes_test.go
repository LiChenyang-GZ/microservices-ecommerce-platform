package consumer

// 单元测试用的"替身"（fake）：代替真数据库和真 DLT，只把收到的东西记下来，
// 这样不起 Kafka、不起 Postgres 也能测 Handler 的判断逻辑。

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/store"
)

// fakeSink 记录每次 Insert；failFirst > 0 时，前几次调用返回一个"数据库暂时连不上"的错误。
type fakeSink struct {
	mu        sync.Mutex // Handler 可能在别的 goroutine 里调用，加锁防止数据竞争
	calls     int        // Insert 一共被调用了几次（包括失败的）
	failFirst int        // 前几次故意失败
	stored    []store.Record
}

var errDBDown = errors.New("connection refused") // 模拟暂时性的数据库错误

func (f *fakeSink) Insert(ctx context.Context, r store.Record) error {
	f.mu.Lock()
	defer f.mu.Unlock() // defer：函数返回时才执行，保证一定解锁
	f.calls++
	if f.calls <= f.failFirst {
		return errDBDown
	}
	f.stored = append(f.stored, r)
	return nil
}

func (f *fakeSink) snapshot() (calls int, stored []store.Record) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls, append([]store.Record(nil), f.stored...) // 复制一份，避免调用方和 fake 共用底层数组
}

// fakeDLT 记录每次 Publish；failFirst > 0 时前几次返回错误（模拟 Kafka 暂时写不进去）。
type fakeDLT struct {
	mu        sync.Mutex
	calls     int
	failFirst int
	published []Message
	reasons   []error
}

func (f *fakeDLT) Publish(ctx context.Context, m Message, reason error) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.calls <= f.failFirst {
		return errors.New("kafka: not enough replicas")
	}
	f.published = append(f.published, m)
	f.reasons = append(f.reasons, reason)
	return nil
}

func (f *fakeDLT) snapshot() (calls int, published []Message) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls, append([]Message(nil), f.published...)
}

// quietLog 是一个把日志丢掉的 logger，测试输出更干净。
var quietLog = slog.New(slog.NewTextHandler(io.Discard, nil))
