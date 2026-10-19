//go:build crashtest

package app

// Only compiled with `go build -tags crashtest`, which the integration tests do.
// It lets a test kill the real binary at an exact point, the way a power cut would:
// os.Exit skips every defer, so nothing is closed or committed on the way out.
//
//	TRACKING_TEST_CRASH_AT     before-store | after-store
//	TRACKING_TEST_CRASH_ORDER  only crash on a message with this key (orderId)
//	TRACKING_TEST_CRASH_DELAY  wait this long first, e.g. 7s (lets a pending autocommit happen)

import (
	"os"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/consumer"
)

const crashExitCode = 3

func crashHooks() consumer.Hooks {
	at := os.Getenv("TRACKING_TEST_CRASH_AT")
	order := os.Getenv("TRACKING_TEST_CRASH_ORDER")
	delay, _ := time.ParseDuration(os.Getenv("TRACKING_TEST_CRASH_DELAY"))
	crash := func(m consumer.Message) {
		if order != "" && string(m.Key) != order {
			return
		}
		time.Sleep(delay)
		os.Exit(crashExitCode)
	}
	switch at {
	case "before-store":
		return consumer.Hooks{BeforeStore: crash}
	case "after-store":
		return consumer.Hooks{AfterStore: crash}
	default:
		return consumer.Hooks{}
	}
}
