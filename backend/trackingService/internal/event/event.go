// Package event reads the Kafka event envelope defined in backend/contracts.
package event

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
)

// Topic names, same as contracts Topics.java.
const (
	OrderEvents    = "order-events"
	DeliveryEvents = "delivery-events"
)

// ErrInvalid marks a message that can never be processed (rule 6): it goes to the DLT.
var ErrInvalid = errors.New("invalid event")

// Envelope is the shell every event travels in (contracts README §2).
type Envelope struct {
	EventID    string          `json:"eventId"`
	EventType  string          `json:"eventType"`
	OrderID    string          `json:"orderId"`
	OccurredAt time.Time       `json:"occurredAt"`
	Payload    json.RawMessage `json:"payload"`
}

// topicOf lists the 11 event types and the topic each one belongs to.
var topicOf = map[string]string{
	"OrderPlaced":     OrderEvents,
	"OrderPaid":       OrderEvents,
	"PaymentFailed":   OrderEvents,
	"OrderCancelled":  OrderEvents,
	"OrderRefunded":   OrderEvents,
	"DeliveryCreated": DeliveryEvents,
	"PickedUp":        DeliveryEvents,
	"Delivering":      DeliveryEvents,
	"Received":        DeliveryEvents,
	"Lost":            DeliveryEvents,
	"Cancelled":       DeliveryEvents,
}

// TopicOf returns the topic an event type belongs to; ok is false for unknown types.
func TopicOf(eventType string) (topic string, ok bool) {
	topic, ok = topicOf[eventType]
	return topic, ok
}

// KnownTypes returns every event type tracking understands, sorted.
func KnownTypes() []string {
	out := make([]string, 0, len(topicOf))
	for t := range topicOf {
		out = append(out, t)
	}
	sort.Strings(out)
	return out
}

// wire is the envelope as it arrives. Pointer fields stay nil when a field is
// missing or null, which a plain struct cannot tell apart from an empty value:
// encoding/json silently leaves zero values for missing fields.
type wire struct {
	EventID    *string         `json:"eventId"`
	EventType  *string         `json:"eventType"`
	OrderID    *string         `json:"orderId"`
	OccurredAt *time.Time      `json:"occurredAt"` // RFC 3339; a timestamp without a zone is rejected
	Payload    json.RawMessage `json:"payload"`
}

// Parse reads one Kafka message value with the same rules as Java's EventCodec:
// all five fields present and non-null, eventId a 36-character UUID, orderId and
// eventType not blank, payload a JSON object. Unknown extra fields are ignored
// (contracts README §7-5). Any error wraps ErrInvalid.
func Parse(data []byte) (Envelope, error) {
	var w wire
	if err := json.Unmarshal(data, &w); err != nil {
		return Envelope{}, invalid("%v", err)
	}
	if w.EventID == nil {
		return Envelope{}, invalid("eventId is required")
	}
	// uuid.Parse also accepts forms like "urn:uuid:…" and 32 hex digits; Java only takes the 36-character form.
	id, err := uuid.Parse(*w.EventID)
	if err != nil || len(*w.EventID) != 36 {
		return Envelope{}, invalid("eventId %q is not a UUID", *w.EventID)
	}
	if w.EventType == nil || strings.TrimSpace(*w.EventType) == "" {
		return Envelope{}, invalid("eventType is required")
	}
	if w.OrderID == nil || strings.TrimSpace(*w.OrderID) == "" {
		return Envelope{}, invalid("orderId is required")
	}
	if w.OccurredAt == nil {
		return Envelope{}, invalid("occurredAt is required")
	}
	// A missing payload leaves the slice empty; a JSON null arrives as the text "null".
	if p := bytes.TrimSpace(w.Payload); len(p) == 0 || p[0] != '{' {
		return Envelope{}, invalid("payload must be a JSON object")
	}
	return Envelope{
		EventID:    id.String(), // canonical lower-case form
		EventType:  *w.EventType,
		OrderID:    *w.OrderID,
		OccurredAt: *w.OccurredAt,
		Payload:    w.Payload,
	}, nil
}

func invalid(format string, args ...any) error {
	return fmt.Errorf("%w: %s", ErrInvalid, fmt.Sprintf(format, args...))
}
