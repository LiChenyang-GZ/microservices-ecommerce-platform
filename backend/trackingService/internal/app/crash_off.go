//go:build !crashtest

package app

import "github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/consumer"

// crashHooks is empty in normal builds. See crash_on.go.
func crashHooks() consumer.Hooks { return consumer.Hooks{} }
