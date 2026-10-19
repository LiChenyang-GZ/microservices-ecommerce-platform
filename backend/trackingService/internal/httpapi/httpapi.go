// Package httpapi serves GET /api/tracking/orders/{orderId}/timeline.
package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

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
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/tracking/orders/{orderId}/timeline", func(w http.ResponseWriter, r *http.Request) {
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if _, err := v.Validate(r.Context(), token); err != nil {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		entries, err := repo.Timeline(r.Context(), r.PathValue("orderId"))
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		var out []Entry
		for _, e := range entries {
			out = append(out, Entry{EventType: e.EventType, Source: e.Source, OccurredAt: e.OccurredAt.Format(time.RFC3339)})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	})
	return mux
}
