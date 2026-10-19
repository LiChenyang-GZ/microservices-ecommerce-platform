// Command tracking consumes order and delivery events and serves order timelines.
//
//	tracking              run the service (configuration from environment variables)
//	tracking healthcheck  exit 0 if the local service answers /healthz (for Docker HEALTHCHECK)
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/app"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}

	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	// ctx is cancelled on Ctrl+C or SIGTERM (what Kubernetes sends before stopping a pod).
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := app.LoadConfig(os.Getenv)
	if err != nil {
		log.Error("bad config", "err", err)
		os.Exit(2)
	}
	if err := app.Run(ctx, cfg, log); err != nil {
		log.Error("tracking stopped", "err", err)
		os.Exit(1)
	}
	log.Info("tracking stopped")
}

// healthcheck calls /healthz on the local port. The runtime image has no curl.
func healthcheck() int {
	addr := os.Getenv("HTTP_ADDR")
	if addr == "" {
		addr = ":8085"
	}
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get("http://" + addr + "/healthz")
	if err != nil {
		return 1
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
