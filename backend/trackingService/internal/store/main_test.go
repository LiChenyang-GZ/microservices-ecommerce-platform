//go:build integration

package store

// 这个包的集成测试共用一个真的 Postgres 容器（Testcontainers）。
// 第一行的 //go:build integration 是 build tag：平时 `go test ./...` 不编译这个文件，
// 要加 `-tags integration` 才跑。所以平时的测试几秒就跑完，不需要 Docker。

import (
	"context"
	"fmt"
	"os"
	"testing"

	"github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/examples"
)

var dbURL string // 容器起来后的连接地址，给本包所有测试用

// TestMain 是 Go 测试的总入口：本包所有测试跑之前先执行它，m.Run() 才真正跑测试。
// 在这里起一次容器，所有测试共用，比每个测试起一次快得多。
func TestMain(m *testing.M) {
	examples.FixDockerSocketOnWindows() // Windows 上的 Docker Desktop 需要这一行，原因见函数注释
	ctx := context.Background()
	// 起一个 Postgres 16 容器；BasicWaitStrategies 会等到数据库真的能连上才返回
	pg, err := postgres.Run(ctx, "postgres:16-alpine",
		postgres.WithDatabase("tracking_db"),
		postgres.WithUsername("tracking"),
		postgres.WithPassword("tracking"),
		postgres.BasicWaitStrategies(),
	)
	if err != nil {
		fmt.Fprintln(os.Stderr, "start postgres:", err)
		os.Exit(1)
	}
	dbURL = pg.MustConnectionString(ctx, "sslmode=disable")
	code := m.Run()       // 跑本包的所有测试
	_ = pg.Terminate(ctx) // 跑完删掉容器
	os.Exit(code)
}

// newStore 给每个测试一个干净的表：连上库、建表、清空。
func newStore(t *testing.T) *Store {
	t.Helper() // 出错时报告调用 newStore 的那一行，而不是这里
	ctx := context.Background()
	s, err := Open(ctx, dbURL)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(s.Close) // 测试结束自动关连接
	if err := s.Migrate(ctx); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	// 每个测试从空表开始，互不影响。表还不存在时（空壳实现）忽略这个错误
	_, _ = s.pool.Exec(ctx, "TRUNCATE tracking_events")
	return s
}

// countRows 数一下表里有几行（不经过被测代码，直接查库）。
func countRows(t *testing.T, s *Store) int {
	t.Helper()
	var n int
	if err := s.pool.QueryRow(context.Background(), "SELECT count(*) FROM tracking_events").Scan(&n); err != nil {
		t.Fatalf("count rows: %v", err)
	}
	return n
}
