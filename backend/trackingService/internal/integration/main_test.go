//go:build integration

// Package integration 放需要真 Kafka + 真 Postgres 一起跑的测试。
package integration

// 这里的测试最接近真实运行：Testcontainers 起一个真的 Kafka（KRaft 单节点）和一个真的 Postgres，
// 测试代码往 Kafka 里发事件，tracking 的消费者去读、写库，然后检查库里的结果。
// 所有测试共用这两个容器（在 TestMain 里起一次）。为了互不干扰，每个测试用自己的 orderId
// 和自己的消费者组名。

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go/modules/kafka"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/event"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/examples"
)

var (
	brokers []string // Kafka 地址，形如 127.0.0.1:54321
	dbURL   string   // Postgres 连接串
)

func TestMain(m *testing.M) {
	os.Exit(run(m)) // 包一层 run，让 defer 能在 os.Exit 之前执行
}

func run(m *testing.M) int {
	examples.FixDockerSocketOnWindows() // Windows 上的 Docker Desktop 需要这一行，原因见函数注释
	ctx := context.Background()

	// 1) 起 Kafka。confluent-local 是 testcontainers 官方 Kafka 模块默认的镜像，KRaft 模式（不需要 ZooKeeper）
	kc, err := kafka.Run(ctx, "confluentinc/confluent-local:7.5.0", kafka.WithClusterID("tracking-test"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "start kafka:", err)
		return 1
	}
	defer kc.Terminate(ctx)
	if brokers, err = kc.Brokers(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "kafka brokers:", err)
		return 1
	}

	// 2) 起 Postgres
	pg, err := postgres.Run(ctx, "postgres:16-alpine",
		postgres.WithDatabase("tracking_db"),
		postgres.WithUsername("tracking"),
		postgres.WithPassword("tracking"),
		postgres.BasicWaitStrategies(),
	)
	if err != nil {
		fmt.Fprintln(os.Stderr, "start postgres:", err)
		return 1
	}
	defer pg.Terminate(ctx)
	dbURL = pg.MustConnectionString(ctx, "sslmode=disable")

	// 3) 建 topic。生产环境里 topic 由第 2 波的人建；这里每个 topic 3 个分区，
	//    这样 rebalance 测试里两个消费者才有分区可分。DLT 也提前建好。
	if err := createTopics(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "create topics:", err)
		return 1
	}

	// 4) 编译一个带崩溃开关的 tracking 程序，给崩溃测试用（见 binary_test.go）
	dir, err := os.MkdirTemp("", "tracking-it-")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer os.RemoveAll(dir)
	if trackingBin, err = buildBinary(dir); err != nil {
		fmt.Fprintln(os.Stderr, "build tracking:", err)
		return 1
	}

	return m.Run()
}

func createTopics(ctx context.Context) error {
	cl, err := kgo.NewClient(kgo.SeedBrokers(brokers...))
	if err != nil {
		return err
	}
	defer cl.Close()
	adm := kadm.NewClient(cl) // kadm：franz-go 的管理接口（建 topic、查 offset、删消费者组）
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	topics := []string{event.OrderEvents, event.DeliveryEvents, event.OrderEvents + ".DLT", event.DeliveryEvents + ".DLT"}
	resp, err := adm.CreateTopics(ctx, 3, 1, nil, topics...) // 3 个分区，1 个副本（单节点）
	if err != nil {
		return err
	}
	return resp.Error()
}
