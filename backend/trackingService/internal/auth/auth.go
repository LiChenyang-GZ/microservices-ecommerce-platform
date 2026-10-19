// Package auth checks a request's token by calling store (design: interaction 12, ADR-14).
package auth

import (
	"bytes"
	"context"
	"encoding/json"
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

// Validate asks store who owns token. Same behaviour as the Java services'
// TokenValidationClient: anything that is not a clear "valid" counts as unauthorized.
func (c *Client) Validate(ctx context.Context, token string) (User, error) {
	if token == "" {
		return User{}, ErrUnauthorized
	}
	body, _ := json.Marshal(map[string]string{"token": token})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/api/user/validate-token", bytes.NewReader(body))
	if err != nil {
		return User{}, ErrUnauthorized
	}
	req.Header.Set("Content-Type", "application/json")
	client := c.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return User{}, ErrUnauthorized
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return User{}, ErrUnauthorized
	}
	var out struct {
		Valid  bool   `json:"valid"`
		UserID int64  `json:"userId"`
		Email  string `json:"email"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || !out.Valid {
		return User{}, ErrUnauthorized
	}
	return User{ID: out.UserID, Email: out.Email}, nil
}
