package app

// 配置的单元测试。LoadConfig 不直接读 os.Getenv，而是接收一个 getenv 函数，
// 所以测试可以传一个假的环境（map），不用真的改电脑上的环境变量。

import (
	"strings"
	"testing"
)

// envFrom 把一个 map 变成 getenv 函数：查不到就返回空字符串，和 os.Getenv 一样。
func envFrom(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

// TestLoadConfig_AllSet：环境变量都给了 → 每个字段读对；没给的用默认值。
func TestLoadConfig_AllSet(t *testing.T) {
	cfg, err := LoadConfig(envFrom(map[string]string{
		"DATABASE_URL":  "postgres://tracking:secret@postgres:5432/tracking_db",
		"KAFKA_BROKERS": "kafka:9092,kafka2:9092",
		"STORE_URL":     "http://store:8082",
	}))
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.DatabaseURL != "postgres://tracking:secret@postgres:5432/tracking_db" {
		t.Errorf("DatabaseURL = %q", cfg.DatabaseURL)
	}
	if len(cfg.Brokers) != 2 || cfg.Brokers[0] != "kafka:9092" || cfg.Brokers[1] != "kafka2:9092" {
		t.Errorf("Brokers = %v", cfg.Brokers)
	}
	if cfg.StoreURL != "http://store:8082" {
		t.Errorf("StoreURL = %q", cfg.StoreURL)
	}
	if cfg.HTTPAddr != ":8085" { // 默认端口
		t.Errorf("HTTPAddr = %q, want :8085", cfg.HTTPAddr)
	}
	if cfg.Group != "tracking" { // 默认消费者组
		t.Errorf("Group = %q, want tracking", cfg.Group)
	}
}

// #37 缺必填的环境变量 → 启动时就报错退出。
// 不然服务会"看起来启动成功"，直到第一条消息或第一个请求才莫名其妙地失败。
func TestConfig_MissingRequired_FailsFast(t *testing.T) {
	for _, missing := range []string{"DATABASE_URL", "KAFKA_BROKERS", "STORE_URL"} {
		t.Run(missing, func(t *testing.T) {
			env := map[string]string{
				"DATABASE_URL":  "postgres://x@db/tracking_db",
				"KAFKA_BROKERS": "kafka:9092",
				"STORE_URL":     "http://store:8082",
			}
			delete(env, missing) // 每次去掉一个
			_, err := LoadConfig(envFrom(env))
			if err == nil {
				t.Fatalf("LoadConfig accepted a config without %s", missing)
			}
			if !strings.Contains(err.Error(), missing) { // 错误信息要说清楚缺的是哪个
				t.Errorf("error %q does not name %s", err, missing)
			}
		})
	}
}
