// Package auth checks a request's token by calling store (design: interaction 12, ADR-14).
package auth

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"
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

// Timeout bounds a call to store when Client.HTTP is nil.
// http.DefaultClient has no timeout at all: a hung store would hang every request.
const Timeout = 2 * time.Second

var defaultHTTP = &http.Client{Timeout: Timeout}

// Validate asks store who owns token.
//
// Unlike the Java services' TokenValidationClient, which treats every failure as
// "invalid token", it tells two cases apart (G7):
//   - store answered "not valid" (401/403, or valid=false) → ErrUnauthorized → 401
//   - store did not answer properly (down, timeout, 5xx)   → ErrUnavailable  → 503
//
// A 401 makes the frontend log the user out; a 503 says "try again later", which is the truth.
func (c *Client) Validate(ctx context.Context, token string) (User, error) {
	if token == "" {
		return User{}, ErrUnauthorized
	}
	body, _ := json.Marshal(map[string]string{"token": token})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/api/user/validate-token", bytes.NewReader(body))
	if err != nil {
		return User{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	req.Header.Set("Content-Type", "application/json")
	client := c.HTTP
	if client == nil {
		client = defaultHTTP
	}
	resp, err := client.Do(req)
	if err != nil {
		return User{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusUnauthorized, http.StatusForbidden:
		return User{}, ErrUnauthorized
	default:
		return User{}, fmt.Errorf("%w: store returned %d", ErrUnavailable, resp.StatusCode)
	}
	var out struct {
		Valid  bool   `json:"valid"`
		UserID int64  `json:"userId"`
		Email  string `json:"email"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return User{}, fmt.Errorf("%w: unreadable store response: %v", ErrUnavailable, err)
	}
	if !out.Valid {
		return User{}, ErrUnauthorized
	}
	return User{ID: out.UserID, Email: out.Email}, nil
}
