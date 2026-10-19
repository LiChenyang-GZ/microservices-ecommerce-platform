// Command tracking consumes order and delivery events and serves order timelines.
package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/app"
)

func main() {
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
}
