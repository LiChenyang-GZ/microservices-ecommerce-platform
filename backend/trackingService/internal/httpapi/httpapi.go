// Package httpapi serves GET /api/tracking/orders/{orderId}/timeline.
package httpapi

import (
	"context"
	"encoding/json"
	"errors"
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

// pinger is implemented by *store.Store; /readyz uses it when the repository has it.
type pinger interface {
	Ping(ctx context.Context) error
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

	// Go 1.22+ patterns: method and {orderId} wildcard, read with r.PathValue.
	mux.HandleFunc("GET /api/tracking/orders/{orderId}/timeline", func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || token == "" {
			writeError(w, http.StatusUnauthorized, "please log in") // no token: do not bother store
			return
		}
		user, err := v.Validate(r.Context(), token)
		switch {
		case errors.Is(err, auth.ErrUnauthorized):
			writeError(w, http.StatusUnauthorized, "invalid or expired token")
			return
		case err != nil:
			log.Warn("token check unavailable", "err", err)
			writeError(w, http.StatusServiceUnavailable, "please try again later")
			return
		}

		orderID := r.PathValue("orderId")
		// Ownership (G5): only the user who placed the order sees it. Someone else's
		// order and an order we have no OrderPlaced for get the same 404, so the
		// answer does not reveal which order ids exist.
		owner, found, err := repo.Owner(r.Context(), orderID)
		if err != nil {
			log.Error("owner lookup failed", "orderId", orderID, "err", err)
			writeError(w, http.StatusServiceUnavailable, "please try again later") // never echo the database error
			return
		}
		if !found || owner != user.ID {
			writeError(w, http.StatusNotFound, "order not found")
			return
		}

		entries, err := repo.Timeline(r.Context(), orderID)
		if err != nil {
			log.Error("timeline query failed", "orderId", orderID, "err", err)
			writeError(w, http.StatusServiceUnavailable, "please try again later")
			return
		}
		out := make([]Entry, 0, len(entries)) // never encode a nil slice: that is JSON null, not []
		for _, e := range entries {
			out = append(out, Entry{
				EventType:  e.EventType,
				Source:     e.Source,
				OccurredAt: e.OccurredAt.UTC().Format(time.RFC3339Nano), // "…Z", fractions only when present
			})
		}
		writeJSON(w, http.StatusOK, out)
	})

	// Liveness: the process is up. Kubernetes restarts the pod when this fails.
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	// Readiness: the database answers. Kubernetes stops routing traffic while this fails.
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if p, ok := repo.(pinger); ok {
			ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
			defer cancel()
			if err := p.Ping(ctx); err != nil {
				writeError(w, http.StatusServiceUnavailable, "database unavailable")
				return
			}
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})
	return mux
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeError uses the same shape as the Java services' filters: {"error": …, "message": …}.
func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": http.StatusText(status), "message": message})
}
