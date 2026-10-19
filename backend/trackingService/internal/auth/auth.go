// Package auth checks a request's token by calling store (design: interaction 12, ADR-14).
package auth

import (
	"context"
	"errors"
	"net/http"
)

var (
	// ErrUnauthorized: store says the token is missing, invalid or expired → 401.
	ErrUnauthorized = errors.New("unauthorized")
	// ErrUnavailable: store could not answer (down, timeout, 5xx) → 503.
	ErrUnavailable = errors.New("token check unavailable")
)

// User is who the token belongs to.
type User struct {
	ID    int64
	Email string
}

// Client calls store's POST /api/user/validate-token.
type Client struct {
	BaseURL string       // e.g. http://store:8082
	HTTP    *http.Client // nil = http.DefaultClient
}

// Validate asks store who owns token.
func (c *Client) Validate(ctx context.Context, token string) (User, error) {
	return User{}, ErrUnauthorized
}
