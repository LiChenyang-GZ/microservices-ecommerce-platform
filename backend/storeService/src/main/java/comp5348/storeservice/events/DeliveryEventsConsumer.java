package comp5348.storeservice.events;

import com.comp5348.contracts.events.DeliveryEventType;
import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.InvalidEventException;
import com.comp5348.contracts.events.Topics;
import com.comp5348.contracts.events.payload.DeliveryEventPayload;
import comp5348.storeservice.service.DeliveryEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * Reads delivery-events (consumer group "store", from spring.kafka.consumer.group-id) and hands each event to
 * DeliveryEventService. This class only checks the message; anything wrong with the message itself is an
 * InvalidEventException, which KafkaConsumerConfig sends straight to delivery-events.DLT.
 *
 * The offset is committed after this method returns (ack-mode=record), i.e. after the database transaction in
 * DeliveryEventService has committed (rule 4). A crash in between means the event comes again; status never
 * going backwards makes the repeat harmless.
 */
@Component
public class DeliveryEventsConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryEventsConsumer.class);

    private final DeliveryEventService deliveryEventService;

    public DeliveryEventsConsumer(DeliveryEventService deliveryEventService) {
        this.deliveryEventService = deliveryEventService;
    }

    @KafkaListener(topics = Topics.DELIVERY_EVENTS)
    public void onDeliveryEvent(ConsumerRecord<String, String> record) throws InvalidEventException {
        EventEnvelope event = EventCodec.parse(record.value());

        Optional<DeliveryEventType> type = Arrays.stream(DeliveryEventType.values())
                .filter(t -> t.wireName().equals(event.eventType()))
                .findFirst();
        if (type.isEmpty()) {
            // A newer delivery may add event types before store knows them: skip, do not dead-letter
            logger.warn("Ignoring unknown delivery event type {} (eventId={})", event.eventType(), event.eventId());
            return;
        }

        DeliveryEventPayload payload = EventCodec.payload(event, DeliveryEventPayload.class);
        if (!type.get().deliveryStatus().equals(payload.status())) {
            throw new InvalidEventException("Event " + event.eventType() + " carries status " + payload.status()
                    + ", expected " + type.get().deliveryStatus(), null);
        }
        long orderId;
        try {
            orderId = Long.parseLong(event.orderId());
        } catch (NumberFormatException e) {
            throw new InvalidEventException("orderId is not a store order id: " + event.orderId(), e);
        }

        deliveryEventService.apply(orderId, type.get(), payload.deliveryId(), event.eventId().toString());
    }
}
