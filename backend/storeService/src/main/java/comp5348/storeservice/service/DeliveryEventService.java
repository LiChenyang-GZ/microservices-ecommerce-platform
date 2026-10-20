package comp5348.storeservice.service;

import com.comp5348.contracts.events.DeliveryEventType;
import comp5348.storeservice.adapter.EmailAdapter;
import comp5348.storeservice.events.UnknownOrderException;
import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies one delivery-events event to its order (replaces the old webhook, design step 4).
 *
 * One transaction per event: lock the order row, then change the order and write any REFUND task together.
 * Never calls bank (rule 1): a lost parcel only writes a REFUND task for OutboxProcessor.
 *
 * Status never goes backwards (rule 5). Delivery ranks CREATED < PICKED_UP < DELIVERING < final
 * (RECEIVED / LOST / CANCELLED); an event whose rank is not above the order's current rank is skipped.
 * That also makes duplicates harmless (rule 4): the second copy is never "above" the first.
 */
@Service
public class DeliveryEventService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryEventService.class);

    private static final int NOT_SHIPPABLE = -1; // order not paid: no delivery event should exist yet
    private static final int FINAL = 3;

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final WarehouseService warehouseService;
    private final EmailAdapter emailAdapter;
    private final JdbcTemplate jdbc;

    public DeliveryEventService(OrderRepository orderRepository, AccountRepository accountRepository,
                                OrderService orderService, PaymentService paymentService,
                                WarehouseService warehouseService, EmailAdapter emailAdapter, JdbcTemplate jdbc) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.warehouseService = warehouseService;
        this.emailAdapter = emailAdapter;
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(long orderId, DeliveryEventType event, Long deliveryId, String eventId) {
        // Do not wait for a locked order row forever (review C medium 2). After 5 seconds Postgres cancels the
        // wait; the error is "transient", so the consumer backs off and tries again instead of hanging.
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new UnknownOrderException(orderId));

        int current = rank(order.getStatus());
        if (current == NOT_SHIPPABLE) {
            // Delivery and store disagree; the message itself is fine, so no DLT: a human has to look
            logger.error("ALERT delivery_event_unexpected orderId={} orderStatus={} event={} eventId={}",
                    orderId, order.getStatus(), event.wireName(), eventId);
            return;
        }
        if (deliveryId != null && order.getDeliveryId() == null && current < FINAL) {
            order.setDeliveryId(deliveryId); // later emails quote it
        }
        if (rank(event) <= current) {
            logger.info("Skipping {} for order {}: order is already {} (eventId={})",
                    event.wireName(), orderId, order.getStatus(), eventId);
            return;
        }

        switch (event) {
            case PICKED_UP -> moveTo(order, OrderStatus.PICKED_UP);
            case DELIVERING -> moveTo(order, OrderStatus.IN_TRANSIT);
            case RECEIVED -> {
                moveTo(order, OrderStatus.DELIVERED);
                warehouseService.recordOutTransaction(order.getProduct().getId(), order.getQuantity(), orderId);
            }
            case LOST -> {
                // Timeline B: LOST + REFUND task in this transaction. Stock is not released: the goods left.
                moveTo(order, OrderStatus.LOST);
                warehouseService.recordOutTransaction(order.getProduct().getId(), order.getQuantity(), orderId);
                paymentService.requestRefund(orderId, "Auto refund: package lost during delivery");
            }
            case CANCELLED -> {
                // Delivery cancelled but the order is still paid: store committed nothing (crash after the cancel
                // call, a timed-out call, or the user cancelled on the delivery page). Finish the cancel here.
                orderService.finishCancel(order, "Cancelled by delivery", OrderService.CANCEL_USER);
            }
            case DELIVERY_CREATED -> { } // rank 0, never above current; handled by the deliveryId line above
        }
    }

    private void moveTo(Order order, OrderStatus status) {
        order.setStatus(status);
        orderRepository.save(order);
        logger.info("Order {} is now {}", order.getId(), status);
        String email = accountRepository.findById(order.getUserId()).map(a -> a.getEmail()).orElse(null);
        if (email == null || email.isEmpty()) {
            return;
        }
        String orderId = String.valueOf(order.getId());
        Long deliveryId = order.getDeliveryId();
        // Email only after the commit: a rolled-back (and retried) transaction must not send it twice
        afterCommit(() -> {
            switch (status) {
                case PICKED_UP -> { if (deliveryId != null) emailAdapter.sendOrderPickedUp(email, orderId, deliveryId); }
                case IN_TRANSIT -> { if (deliveryId != null) emailAdapter.sendOrderDelivering(email, orderId, deliveryId); }
                case DELIVERED -> { if (deliveryId != null) emailAdapter.sendOrderDelivered(email, orderId, deliveryId); }
                case LOST -> emailAdapter.sendOrderLost(email, orderId,
                        "Package lost during delivery, system has initiated refund");
                default -> { }
            }
        });
    }

    static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    LoggerFactory.getLogger(DeliveryEventService.class).warn("Email after commit failed: {}", e.getMessage());
                }
            }
        });
    }

    private static int rank(OrderStatus status) {
        return switch (status) {
            case PENDING_STOCK_HOLD, PLACED, PENDING_PAYMENT -> NOT_SHIPPABLE;
            case PAID, PROCESSING -> 0;
            case PICKED_UP -> 1;
            case IN_TRANSIT -> 2;
            case DELIVERED, LOST, REFUNDED, CANCELLED, CANCELLED_SYSTEM -> FINAL;
        };
    }

    private static int rank(DeliveryEventType event) {
        return switch (event) {
            case DELIVERY_CREATED -> 0;
            case PICKED_UP -> 1;
            case DELIVERING -> 2;
            case RECEIVED, LOST, CANCELLED -> FINAL;
        };
    }
}
