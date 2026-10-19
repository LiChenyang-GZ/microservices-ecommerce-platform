// Package httpapi serves GET /api/tracking/orders/{orderId}/timeline.
package httpapi

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/auth"
	"github.com/LiChenyang-GZ/microservices-ecommerce-platform/backend/trackingService/internal/store"
)

// Repository is the part of the store the API reads.
type Repository interface {
	Timeline(ctx context.Context, orderID string) ([]store.Entry, error)
	Owner(ctx context.Context, orderID string) (userID int64, found bool, err error)
}

// Validator checks a bearer token (auth.Client in production).
type Validator interface {
	Validate(ctx context.Context, token string) (auth.User, error)
}

// Entry is one line of the JSON response.
type Entry struct {
	EventType  string `json:"eventType"`
	Source     string `json:"source"`
	OccurredAt string `json:"occurredAt"`
}

// New builds the HTTP handler.
func New(repo Repository, v Validator, log *slog.Logger) http.Handler {
	return http.NotFoundHandler()
}
