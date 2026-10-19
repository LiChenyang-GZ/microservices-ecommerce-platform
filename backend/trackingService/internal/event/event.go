// Package event reads the Kafka event envelope defined in backend/contracts.
package event

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"time"
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

// Parse reads one Kafka message value. Any error wraps ErrInvalid.
func Parse(data []byte) (Envelope, error) {
	return Envelope{}, fmt.Errorf("%w: not implemented", ErrInvalid)
}
