//go:build integration

package integration

// 模拟"真的崩溃"：把 tracking 编译成一个程序，在子进程里跑它，让它在指定的位置 os.Exit。
// os.Exit 会跳过所有 defer：不关连接、不离开消费者组、不提交 offset，和断电一样。
// 如果在测试进程里模拟（比如 cancel 一下），程序会"体面地"关闭，正好掩盖了我们要找的问题。
// 编译时加 -tags crashtest，才会把 internal/app/crash_on.go 里的崩溃开关编进去。

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

var trackingBin string // 编译好的程序路径（TestMain 里设置）

// buildBinary 编译 cmd/tracking 到临时目录。
func buildBinary(dir string) (string, error) {
	_, here, _, _ := runtime.Caller(0)
	moduleRoot := filepath.Join(filepath.Dir(here), "..", "..") // backend/trackingService
	out := filepath.Join(dir, "tracking-crashtest")
	if runtime.GOOS == "windows" {
		out += ".exe"
	}
	cmd := exec.Command("go", "build", "-tags", "crashtest", "-o", out, "./cmd/tracking")
	cmd.Dir = moduleRoot
	cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
	return out, cmd.Run()
}

// crashExitCode 和 internal/app/crash_on.go 里的一致：退出码 3 = 在预定位置崩溃了。
const crashExitCode = 3

// runUntilCrash 用给定的消费者组跑 tracking 程序，等它在 crashAt 那个位置崩溃（只对 key = orderID 的消息）。
func runUntilCrash(t *testing.T, group, orderID, crashAt, delay string) {
	t.Helper()
	cmd := exec.Command(trackingBin)
	cmd.Env = append(os.Environ(),
		"DATABASE_URL="+dbURL,
		"KAFKA_BROKERS="+brokers[0],
		"STORE_URL=http://127.0.0.1:1", // 这个测试不调查询接口
		"HTTP_ADDR=127.0.0.1:0",        // 随机端口，不和别的进程抢
		"KAFKA_GROUP="+group,
		"KAFKA_SESSION_TIMEOUT=6s",
		"TRACKING_TEST_CRASH_AT="+crashAt,
		"TRACKING_TEST_CRASH_ORDER="+orderID,
		"TRACKING_TEST_CRASH_DELAY="+delay,
	)
	if err := cmd.Start(); err != nil {
		t.Fatalf("start tracking: %v", err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		var exit *exec.ExitError
		if !errors.As(err, &exit) || exit.ExitCode() != crashExitCode {
			t.Fatalf("tracking exited with %v, want the simulated crash (exit code %d)", err, crashExitCode)
		}
	case <-time.After(90 * time.Second):
		_ = cmd.Process.Kill()
		t.Fatalf("tracking never reached the crash point %q", crashAt)
	}
}
