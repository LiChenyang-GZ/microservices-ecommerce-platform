package com.comp5348.delivery.service.events;

import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.InvalidEventException;
import com.comp5348.contracts.events.OrderEventType;
import com.comp5348.contracts.events.Topics;
import com.comp5348.contracts.events.payload.OrderPaidPayload;
import com.comp5348.delivery.service.DeliveryService;
import com.comp5348.delivery.service.DeliveryService.CancelOutcome;
import com.comp5348.delivery.service.DeliveryService.CancelResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads order-events (consumer group "delivery", from spring.kafka.consumer.group-id).
 *
 *   OrderPaid      → create the delivery (one row per order; a repeat or an earlier cancel marker means skip)
 *   OrderCancelled → the order was cancelled before it was paid: same cancel-by-order as store's call, so a
 *                    cancel marker blocks a late OrderPaid. If the parcel is already on its way, ALERT only.
 *   anything else  → not delivery's business (interaction table #5): read past it, no DLT
 *
 * A message that cannot be parsed is an InvalidEventException → order-events.DLT (KafkaConsumerConfig).
 * The offset is committed after this method returns (ack-mode=record), after the database transaction (rule 4).
 */
@Component
public class OrderEventsConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private final DeliveryService deliveryService;

    public OrderEventsConsumer(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS)
    public void onOrderEvent(ConsumerRecord<String, String> record) throws InvalidEventException {
        EventEnvelope event = EventCodec.parse(record.value());

        if (OrderEventType.ORDER_PAID.wireName().equals(event.eventType())) {
            OrderPaidPayload paid = EventCodec.payload(event, OrderPaidPayload.class);
            deliveryService.createDelivery(event.orderId(), paid);
        } else if (OrderEventType.ORDER_CANCELLED.wireName().equals(event.eventType())) {
            CancelResult result = deliveryService.cancelByOrderId(event.orderId());
            if (result.outcome() == CancelOutcome.TOO_LATE) {
                // Store cancelled an order it thought was unpaid, but this delivery is already moving:
                // the two services disagree. Nothing to undo here; a person has to look.
                logger.error("ALERT order_cancelled_but_delivery_moving orderId={} deliveryStatus={} eventId={}",
                        event.orderId(), result.status(), event.eventId());
            }
        } else {
            logger.debug("Ignoring order event {} for order {}", event.eventType(), event.orderId());
        }
    }
}
