//go:build integration

package integration

// 失败场景表 C、D 组里必须用真 Kafka 才测得出来的那几行。

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/app"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/consumer"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
)

// #16 写完库、还没提交 offset 时崩溃 → 重启后这条消息会再来一次 → 库里仍然只有一行。
// 这就是"至少一次投递"的代价：不丢，但可能重复（规则 4、笔记 Kafka §记住读到哪了）。
func TestCrashAfterStoreBeforeCommit_NoDuplicateRows(t *testing.T) {
	openStore(t) // 确保表已经建好
	orderID := newOrderID()
	group := "crash-after-store-" + orderID
	ex := exampleNamed(t, examplesFor(t, orderID), "order-events-OrderPlaced.json")
	r := produce(t, ex.Topic, orderID, ex.JSON)

	// 第一次运行：这条消息写进库之后，程序马上"断电"
	runUntilCrash(t, group, orderID, "after-store", "0s")
	if n := rowsFor(t, orderID); n != 1 {
		t.Fatalf("after the crash: %d rows, want 1 (the row was written before the crash)", n)
	}

	// 重启（同一个消费者组）：从上次提交的位置继续读
	var redelivered atomic.Int32 // 重启后这条消息又被处理了几次
	s := openStore(t)
	startConsumer(t, group, s, consumer.Hooks{AfterStore: func(m consumer.Message) {
		if string(m.Key) == orderID {
			redelivered.Add(1)
		}
	}})
	waitCommittedPast(t, group, r) // 等重启后的消费者处理完并提交

	if redelivered.Load() == 0 {
		t.Fatalf("the message was not delivered again after the crash; the scenario did not happen")
	}
	if n := rowsFor(t, orderID); n != 1 {
		t.Errorf("rows = %d, want 1: the redelivered message was stored twice", n)
	}
}

// #17 先提交了 offset、还没写库时崩溃 → 重启后这条消息就再也读不到了（丢了）。
// 崩溃前故意等 7 秒，让 franz-go 的自动提交（默认每 5 秒一次）有机会发生。
func TestCrashBeforeStore_EventNotLost(t *testing.T) {
	openStore(t)
	orderID := newOrderID()
	group := "crash-before-store-" + orderID
	ex := exampleNamed(t, examplesFor(t, orderID), "order-events-OrderPlaced.json")
	produce(t, ex.Topic, orderID, ex.JSON)

	runUntilCrash(t, group, orderID, "before-store", "7s")
	if n := rowsFor(t, orderID); n != 0 {
		t.Fatalf("after the crash: %d rows, want 0 (it crashed before writing)", n)
	}

	s := openStore(t)
	startConsumer(t, group, s, consumer.Hooks{})
	waitFor(t, 60*time.Second, "the event to be stored after restart (otherwise it was lost)",
		func() bool { return rowsFor(t, orderID) == 1 })
}

// #18 坏消息 → 原样写进 order-events.DLT，带上失败原因；后面的正常消息照常处理。
// header 的名字和编码照 Spring Kafka 的 DeadLetterPublishingRecoverer（G2），第 2 波 Java 用默认配置就能对上。
func TestPoisonMessage_GoesToDlt_NextEventStored(t *testing.T) {
	s := openStore(t)
	orderID := newOrderID()
	group := "dlt-" + orderID

	poison := []byte(`this is not an event`)
	bad := produce(t, event.OrderEvents, orderID, poison) // 先发坏消息
	ex := exampleNamed(t, examplesFor(t, orderID), "order-events-OrderPlaced.json")
	produce(t, ex.Topic, orderID, ex.JSON) // 再发一条正常的，同一个 key → 同一个分区，排在坏消息后面

	startConsumer(t, group, s, consumer.Hooks{})
	waitFor(t, 60*time.Second, "the event after the poison message to be stored",
		func() bool { return rowsFor(t, orderID) == 1 })

	// 去 DLT 里找这条坏消息
	dead := findDLT(t, event.OrderEvents+".DLT", orderID)
	if string(dead.Value) != string(poison) {
		t.Errorf("DLT value = %q, want the original bytes %q", dead.Value, poison)
	}
	h := headers(dead)
	if got := string(h["kafka_dlt-original-topic"]); got != event.OrderEvents {
		t.Errorf("kafka_dlt-original-topic = %q, want %q", got, event.OrderEvents)
	}
	if got := h["kafka_dlt-original-partition"]; len(got) != 4 || int32(binary.BigEndian.Uint32(got)) != bad.Partition {
		t.Errorf("kafka_dlt-original-partition = %v, want 4-byte big-endian %d", got, bad.Partition)
	}
	if got := h["kafka_dlt-original-offset"]; len(got) != 8 || int64(binary.BigEndian.Uint64(got)) != bad.Offset {
		t.Errorf("kafka_dlt-original-offset = %v, want 8-byte big-endian %d", got, bad.Offset)
	}
	if got := string(h["kafka_dlt-original-consumer-group"]); got != group {
		t.Errorf("kafka_dlt-original-consumer-group = %q, want %q", got, group)
	}
	if len(h["kafka_dlt-exception-message"]) == 0 {
		t.Errorf("kafka_dlt-exception-message is empty; the DLT must say why")
	}
}

// #24 两个 topic 之间没有顺序：配送事件先到、订单事件后到 → 时间线仍按发生时间排（规则 5）。
func TestCrossTopicArrivalOrder_TimelineSorted(t *testing.T) {
	s := openStore(t)
	orderID := newOrderID()
	all := examplesFor(t, orderID)
	startConsumer(t, "cross-topic-"+orderID, s, consumer.Hooks{})

	// 先发 6 条配送事件（按发生时间），等它们都存好……
	for _, name := range []string{"DeliveryCreated", "PickedUp", "Delivering", "Received", "Lost", "Cancelled"} {
		ex := exampleNamed(t, all, "delivery-events-"+name+".json")
		produce(t, ex.Topic, orderID, ex.JSON)
	}
	waitFor(t, 60*time.Second, "6 delivery events stored", func() bool { return timelineLen(s, orderID) == 6 })
	// ……再发 5 条订单事件。它们发生得更早，到得更晚
	for _, name := range []string{"OrderPlaced", "OrderPaid", "PaymentFailed", "OrderCancelled", "OrderRefunded"} {
		ex := exampleNamed(t, all, "order-events-"+name+".json")
		produce(t, ex.Topic, orderID, ex.JSON)
	}
	waitFor(t, 60*time.Second, "all 11 events stored", func() bool { return timelineLen(s, orderID) == 11 })

	got, err := s.Timeline(context.Background(), orderID)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"OrderPlaced", "OrderPaid", "PaymentFailed", "OrderCancelled", "OrderRefunded", // 02:01–02:05
		"DeliveryCreated", "PickedUp", "Delivering", "Received", "Lost", "Cancelled"} // 02:06–02:11
	for i := range want {
		if got[i].EventType != want[i] {
			var order []string
			for _, e := range got {
				order = append(order, e.EventType)
			}
			t.Fatalf("timeline = %v\n      want %v", order, want)
		}
	}
}

// #25 消费到一半，同一个组里来了第二个实例（滚动更新、扩容）→ 分区重新分配（rebalance）→ 不丢、不重。
func TestRebalance_NoLossNoDuplicates(t *testing.T) {
	s := openStore(t)
	prefix := newOrderID()
	group := "rebalance-" + prefix

	// 20 个订单 × 3 条订单事件 = 60 条，key 不同，分散到 3 个分区
	var recs []*kgo.Record
	for i := 0; i < 20; i++ {
		orderID := fmt.Sprintf("%s-%02d", prefix, i)
		all := examplesFor(t, orderID)
		for _, name := range []string{"OrderPlaced", "OrderPaid", "OrderRefunded"} {
			ex := exampleNamed(t, all, "order-events-"+name+".json")
			recs = append(recs, produce(t, ex.Topic, orderID, ex.JSON))
		}
	}

	// 每条处理慢一点，rebalance 才会发生在中途
	slow := consumer.Hooks{AfterStore: func(consumer.Message) { time.Sleep(50 * time.Millisecond) }}
	startConsumer(t, group, s, slow) // 实例 A
	waitFor(t, 60*time.Second, "instance A to store some events", func() bool { return rowsLike(t, prefix) >= 10 })
	startConsumer(t, group, s, slow) // 实例 B 加入 → rebalance

	waitCommittedPast(t, group, recs...) // 两个实例一起把 60 条都处理完并提交

	if n := rowsLike(t, prefix); n != 60 {
		t.Errorf("rows = %d, want 60 (lost or duplicated during the rebalance)", n)
	}
}

// #26 重放：清空这个订单的数据、把消费者组的 offset 重置到最早 → 时间线和之前一模一样（ADR-11）。
func TestReplay_RebuildsIdenticalTimeline(t *testing.T) {
	s := openStore(t)
	orderID := newOrderID()
	group := "replay-" + orderID
	for _, ex := range examplesFor(t, orderID) {
		produce(t, ex.Topic, orderID, ex.JSON)
	}

	// 第一次：正常消费，记下时间线
	r := startConsumer(t, group, s, consumer.Hooks{})
	waitFor(t, 60*time.Second, "11 events stored", func() bool { return timelineLen(s, orderID) == 11 })
	before, err := s.Timeline(context.Background(), orderID)
	if err != nil {
		t.Fatal(err)
	}
	r.cancel() // 停掉消费者
	select {
	case <-r.done:
	case <-time.After(30 * time.Second):
		t.Fatal("consumer did not stop")
	}

	// 清空数据，并删掉消费者组 = offset 回到最早（一个新的组会从头读）
	execSQL(t, "DELETE FROM tracking_events WHERE order_id = $1", orderID)
	adm := admin(t)
	waitFor(t, 30*time.Second, "consumer group to be deleted", func() bool {
		resp, err := adm.DeleteGroups(context.Background(), group)
		return err == nil && resp.Error() == nil
	})

	// 第二次：重放
	startConsumer(t, group, s, consumer.Hooks{})
	waitFor(t, 60*time.Second, "11 events replayed", func() bool { return timelineLen(s, orderID) == 11 })
	after, err := s.Timeline(context.Background(), orderID)
	if err != nil {
		t.Fatal(err)
	}
	for i := range before {
		b, a := before[i], after[i]
		if a.EventType != b.EventType || a.Source != b.Source || !a.OccurredAt.Equal(b.OccurredAt) {
			t.Fatalf("entry %d differs after replay: before %+v, after %+v", i, b, a)
		}
	}
}

// #27 收到停止信号时正在处理一条消息 → 把它写完、提交，再退出。
func TestGracefulShutdown_FinishesInFlightAndCommits(t *testing.T) {
	s := openStore(t)
	orderID := newOrderID()
	group := "shutdown-" + orderID

	inFlight := make(chan struct{}) // 消息开始处理时关闭这个 channel，通知测试
	var once sync.Once
	r := startConsumer(t, group, s, consumer.Hooks{BeforeStore: func(m consumer.Message) {
		if string(m.Key) == orderID {
			once.Do(func() { close(inFlight) })
			time.Sleep(time.Second) // 处理要花 1 秒，停止信号就在这期间到达
		}
	}})
	ex := exampleNamed(t, examplesFor(t, orderID), "order-events-OrderPlaced.json")
	rec := produce(t, ex.Topic, orderID, ex.JSON)

	select {
	case <-inFlight:
	case <-time.After(60 * time.Second):
		t.Fatal("message never started processing")
	}
	r.cancel() // 相当于 Kubernetes 发来 SIGTERM

	select {
	case err := <-r.done:
		if err != nil {
			t.Errorf("Run returned %v, want nil after a clean shutdown", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("consumer did not stop within 10s of the shutdown signal")
	}
	if n := rowsFor(t, orderID); n != 1 {
		t.Errorf("rows = %d, want 1: the in-flight message was abandoned", n)
	}
	if !committedPast(t, admin(t), group, rec) {
		t.Errorf("the in-flight message was not committed before exit; it will be processed again")
	}
}

// #27（HTTP 部分）停止时，正在进行的查询请求要回答完，然后端口关闭，Run 返回。
func TestAppShutdown_DrainsHTTPAndStopsListening(t *testing.T) {
	orderID := newOrderID()
	// 假 store：校验 token 要 1 秒，这样停止信号到达时请求还在处理中
	fakeStore := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(time.Second)
		_, _ = w.Write([]byte(`{"valid":true,"userId":7,"email":"customer@example.com"}`))
	}))
	defer fakeStore.Close()

	addrCh := make(chan string, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- app.Run(ctx, app.Config{
			DatabaseURL:    dbURL,
			Brokers:        brokers,
			StoreURL:       fakeStore.URL,
			HTTPAddr:       "127.0.0.1:0",
			Group:          "app-" + orderID,
			SessionTimeout: 6 * time.Second,
			OnListening:    func(a net.Addr) { addrCh <- a.String() },
		}, quietLog)
	}()
	var addr string
	select {
	case addr = <-addrCh:
	case <-time.After(30 * time.Second):
		t.Fatal("app never started listening")
	}

	// 发一个查询请求（它会在 store 那里等 1 秒）
	type result struct {
		status int
		err    error
	}
	resCh := make(chan result, 1)
	go func() {
		req, _ := http.NewRequest(http.MethodGet, "http://"+addr+"/api/tracking/orders/"+orderID+"/timeline", nil)
		req.Header.Set("Authorization", "Bearer user-7")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			resCh <- result{err: err}
			return
		}
		resp.Body.Close()
		resCh <- result{status: resp.StatusCode}
	}()
	time.Sleep(300 * time.Millisecond) // 让请求先进到服务里
	cancel()                           // 停止信号

	select {
	case res := <-resCh:
		if res.err != nil {
			t.Errorf("in-flight request failed during shutdown: %v", res.err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("in-flight request never finished")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Errorf("Run returned %v, want nil", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("app.Run did not return within 10s of the shutdown signal")
	}
	// Run 返回之后，端口必须已经关了（不然老进程还在接请求）
	if conn, err := net.DialTimeout("tcp", addr, time.Second); err == nil {
		conn.Close()
		t.Errorf("still accepting connections on %s after Run returned", addr)
	}
}

// ---- 这个文件用到的小工具 ----

// findDLT 从头读一个 DLT topic，找 key = orderID 的那条，最多等 20 秒。
func findDLT(t *testing.T, topic, orderID string) *kgo.Record {
	t.Helper()
	cl, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumeTopics(topic),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart())) // 不加入消费者组，单纯从头读
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for {
		fetches := cl.PollFetches(ctx)
		if ctx.Err() != nil {
			t.Fatalf("no message with key %s in %s", orderID, topic)
		}
		var found *kgo.Record
		fetches.EachRecord(func(r *kgo.Record) {
			if string(r.Key) == orderID {
				found = r
			}
		})
		if found != nil {
			return found
		}
	}
}

// headers 把 Kafka 消息头变成 map，方便查。
func headers(r *kgo.Record) map[string][]byte {
	m := map[string][]byte{}
	for _, h := range r.Headers {
		m[h.Key] = h.Value
	}
	return m
}

// rowsLike 数订单号以 prefix 开头的行。
func rowsLike(t *testing.T, prefix string) int {
	t.Helper()
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	var n int
	if err := conn.QueryRow(ctx, "SELECT count(*) FROM tracking_events WHERE order_id LIKE $1", prefix+"-%").Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// execSQL 直接执行一条 SQL（测试准备数据用）。
func execSQL(t *testing.T, sql string, args ...any) {
	t.Helper()
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	if _, err := conn.Exec(ctx, sql, args...); err != nil {
		t.Fatal(err)
	}
}
