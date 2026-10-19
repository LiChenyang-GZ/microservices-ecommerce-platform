package consumer

import (
	"context"
	"encoding/binary"

	"github.com/twmb/franz-go/pkg/kgo"
)

// Header names and encodings copied from Spring Kafka's DeadLetterPublishingRecoverer
// (KafkaHeaders.DLT_*, DeadLetterRecordManager), so the Java consumers in wave 2 write
// the same DLT records with their default configuration and one admin page can read both.
const (
	hdrOriginalTopic     = "kafka_dlt-original-topic"          // UTF-8
	hdrOriginalPartition = "kafka_dlt-original-partition"      // 4-byte big-endian int
	hdrOriginalOffset    = "kafka_dlt-original-offset"         // 8-byte big-endian long
	hdrOriginalGroup     = "kafka_dlt-original-consumer-group" // UTF-8
	hdrExceptionMessage  = "kafka_dlt-exception-message"       // UTF-8
)

// kafkaDLT writes broken messages to <topic>.DLT: key and value unchanged, reason in headers.
type kafkaDLT struct {
	cl    *kgo.Client
	group string
}

func (d *kafkaDLT) Publish(ctx context.Context, m Message, reason error) error {
	r := &kgo.Record{
		Topic: m.Topic + ".DLT",
		Key:   m.Key,
		Value: m.Value,
		Headers: []kgo.RecordHeader{
			{Key: hdrOriginalTopic, Value: []byte(m.Topic)},
			{Key: hdrOriginalPartition, Value: binary.BigEndian.AppendUint32(nil, uint32(m.Partition))},
			{Key: hdrOriginalOffset, Value: binary.BigEndian.AppendUint64(nil, uint64(m.Offset))},
			{Key: hdrOriginalGroup, Value: []byte(d.group)},
			{Key: hdrExceptionMessage, Value: []byte(reason.Error())},
		},
	}
	// Wait for Kafka to confirm: the offset is committed only after this returns.
	return d.cl.ProduceSync(ctx, r).FirstErr()
}
