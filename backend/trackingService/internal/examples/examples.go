// Package examples loads the shared contract samples in backend/contracts/examples.
// Java's contract test reads the same files, so both languages agree on the format.
package examples

import (
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"testing"
)

// Example is one sample file, named <topic>-<eventType>.json.
type Example struct {
	Name      string // file name
	Topic     string // order-events or delivery-events
	EventType string // e.g. OrderPaid
	JSON      []byte
}

// Dir returns the absolute path of backend/contracts/examples,
// found relative to this source file so tests work from any working directory.
func Dir() string {
	_, here, _, _ := runtime.Caller(0)
	// here = backend/trackingService/internal/examples/examples.go
	return filepath.Join(filepath.Dir(here), "..", "..", "..", "contracts", "examples")
}

// All reads every sample, sorted by file name.
func All(t testing.TB) []Example {
	t.Helper()
	entries, err := os.ReadDir(Dir())
	if err != nil {
		t.Fatalf("read examples dir: %v", err)
	}
	var out []Example
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(Dir(), e.Name()))
		if err != nil {
			t.Fatalf("read %s: %v", e.Name(), err)
		}
		base := strings.TrimSuffix(e.Name(), ".json")
		// The topic itself contains a dash, so split on the last one.
		i := strings.LastIndex(base, "-")
		out = append(out, Example{Name: e.Name(), Topic: base[:i], EventType: base[i+1:], JSON: data})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}
