package com.comp5348.delivery.service;

import com.comp5348.contracts.events.payload.OrderPaidPayload;
import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.DeliveryOrderDTO;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.repository.DeliveryRepository;
import com.comp5348.delivery.service.events.DeliveryEventRecorder;
import com.comp5348.delivery.utils.DeliveryStatus;
import com.comp5348.delivery.utils.StringListConverter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


@Service
public class DeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryService.class);

    private static final StringListConverter ADDRESSES = new StringListConverter();

    // Dependency injection: repository and RabbitMQ template
    private final DeliveryRepository deliveryRepository;
    private final RabbitTemplate rabbitTemplate;

    private final DeliveryEventRecorder eventRecorder;
    private final JdbcTemplate jdbc;

    @Autowired
    public DeliveryService(DeliveryRepository deliveryRepository, RabbitTemplate rabbitTemplate,
                           DeliveryEventRecorder eventRecorder, JdbcTemplate jdbc) {
        this.deliveryRepository = deliveryRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.eventRecorder = eventRecorder;
        this.jdbc = jdbc;
    }

    /**
     * Create the delivery for an order (sequence A: delivery reads OrderPaid and creates the delivery itself).
     *
     * One row per order: INSERT ... ON CONFLICT (order_id) DO NOTHING. If the row is already there, no delivery is
     * created: either this OrderPaid is a repeat (rule 4, Kafka delivers at least once), or a cancel got here first
     * and left a cancel marker (sequence C). Either way there is no second delivery and no second DeliveryCreated.
     * If the row is CANCELLED, Cancelled is published once more, so store cannot be left with a paid order.
     *
     * @return the new delivery, or empty if this order already had a row
     */
    @Transactional
    public Optional<DeliveryOrderDTO> createDelivery(DeliveryRequest request) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = jdbc.update("""
                INSERT INTO delivery (version, order_id, delivery_status, user_name, to_address, from_address,
                                      product_name, email, quantity, creation_time, update_time, retry_count)
                VALUES (0, ?, 'CREATED', ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (order_id) DO NOTHING""",
                request.getOrderId(), request.getUserName(), request.getToAddress(),
                ADDRESSES.convertToDatabaseColumn(request.getFromAddress()), request.getProductName(),
                request.getEmail(), request.getQuantity(), now, now);
        if (inserted == 0) {
            Delivery existing = deliveryRepository.findByOrderIdForUpdate(request.getOrderId()).orElseThrow();
            if (existing.getDeliveryStatus() == DeliveryStatus.CANCELLED) {
                // Paid, but cancelled here first (review 4b #2). The earlier Cancelled may have reached store before
                // the order existed or was paid, and been dropped. Say it again now that store has a paid order:
                // store finishes the cancel and refunds, or skips it if the order is already cancelled.
                logger.info("Order {} was paid but its delivery is cancelled: publishing Cancelled again",
                        request.getOrderId());
                eventRecorder.recordCancelMarker(existing);
                return Optional.empty();
            }
            logger.info("Order {} already has a delivery row (repeat OrderPaid), skipping", request.getOrderId());
            return Optional.empty();
        }

        Delivery savedDelivery = deliveryRepository.findByOrderId(request.getOrderId()).orElseThrow();
        logger.info("Delivery {} created for order {}", savedDelivery.getId(), request.getOrderId());
        // DeliveryCreated goes out with the new row, in the same transaction (rule 2)
        eventRecorder.recordStatus(savedDelivery);

        // Start the simulated delivery only once the row is committed, and only once. Sent inside the
        // transaction, a fast worker could look for the row before it exists and drop the job (B12).
        Long id = savedDelivery.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, id);
                logger.info("Sent new task ID {} to queue {}", id, RabbitMQConfig.DELIVERY_QUEUE_NAME);
            }
        });

        return Optional.of(new DeliveryOrderDTO(savedDelivery));
    }

    /** OrderPaid carries everything a delivery needs, so delivery never calls store back */
    @Transactional
    public Optional<DeliveryOrderDTO> createDelivery(String orderId, OrderPaidPayload paid) {
        DeliveryRequest request = new DeliveryRequest();
        request.setOrderId(orderId);
        request.setEmail(paid.customerEmail());
        request.setUserName(paid.customerName());
        request.setToAddress(paid.toAddress());
        request.setFromAddress(paid.fromAddresses());
        request.setProductName(paid.productName());
        request.setQuantity(paid.quantity());
        return createDelivery(request);
    }

    /**
     * Cancel a delivery task (verify if it belongs to the specified user)
     * @param deliveryId ID of the delivery task to be cancelled
     * @param userEmail User email (used to verify if order belongs to this user)
     * @return true if successfully cancelled; false if cannot be cancelled due to status or other reasons
     */
    @Transactional
    public boolean cancelDelivery(Long deliveryId, String userEmail) {
        logger.info("Received cancellation request for delivery ID: {}, user: {}", deliveryId, userEmail);

        // 1. Find order by ID
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);

        // If order doesn't exist, return failure directly
        if (deliveryOptional.isEmpty()) {
            logger.warn("Cannot cancel: Delivery ID {} not found in database", deliveryId);
            return false;
        }

        Delivery deliveryToCancel = deliveryOptional.get();

        // 2. Verify if order belongs to this user (a cancel marker has no email, so it never matches)
        if (!userEmail.equals(deliveryToCancel.getEmail())) {
            logger.warn("Cannot cancel: User {} attempted to cancel delivery order {} that doesn't belong to them (belongs to {})",
                    userEmail, deliveryId, deliveryToCancel.getEmail());
            return false;
        }

        DeliveryStatus currentStatus = deliveryToCancel.getDeliveryStatus();

        // 3. Core business rule: cancellation only allowed in specific statuses
        // Only CREATED can be cancelled (cancel API table: PICKED_UP and later is TOO_LATE)
        if (currentStatus == DeliveryStatus.CREATED) {

            // 4. Execute cancellation
            deliveryToCancel.setDeliveryStatus(DeliveryStatus.CANCELLED);
            deliveryToCancel.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.save(deliveryToCancel);
            logger.info("Delivery ID: {} status successfully updated to -> CANCELLED", savedDelivery.getId());

            // 5. Cancelled event in the same transaction; store reads it and completes the cancel (refund, stock)
            eventRecorder.recordStatus(savedDelivery);

            return true; // Return success

        } else {
            // 6. If status doesn't meet conditions, reject cancellation
            logger.warn("Cannot cancel delivery ID: {}, current status is {}, cannot be revoked.",
                    deliveryId, currentStatus);
            return false; // Return failure
        }
    }

    /**
     * Cancel a delivery task (no user verification, for internal calls)
     * @deprecated Please use cancelDelivery(Long, String) method
     */
    @Deprecated
    @Transactional
    public boolean cancelDelivery(Long deliveryId) {
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);
        if (deliveryOptional.isEmpty()) {
            return false;
        }
        Delivery deliveryToCancel = deliveryOptional.get();
        DeliveryStatus currentStatus = deliveryToCancel.getDeliveryStatus();

        // Only CREATED can be cancelled (cancel API table: PICKED_UP and later is TOO_LATE)
        if (currentStatus == DeliveryStatus.CREATED) {
            deliveryToCancel.setDeliveryStatus(DeliveryStatus.CANCELLED);
            deliveryToCancel.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.save(deliveryToCancel);
            eventRecorder.recordStatus(savedDelivery);
            return true;
        }
        return false;
    }

    /**
     * Get detailed information of a single delivery task by ID (verify if it belongs to specified user)
     * @param deliveryId Delivery task ID
     * @param userEmail User email (used to verify if order belongs to this user)
     * @return Delivery task DTO, returns null if not found or doesn't belong to user
     */
    @Transactional(readOnly = true)
    public DeliveryOrderDTO getDeliveryOrder(Long deliveryId, String userEmail) {
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);

        if (deliveryOptional.isEmpty()) {
            return null;
        }

        Delivery delivery = deliveryOptional.get();

        // Verify if order belongs to this user
        if (!userEmail.equals(delivery.getEmail())) {
            logger.warn("User {} attempted to access delivery {} which belongs to {}",
                    userEmail, deliveryId, delivery.getEmail());
            return null;
        }

        return new DeliveryOrderDTO(delivery);
    }

    /**
     * Get detailed information of a single delivery task by ID (no user verification, for internal calls)
     * @deprecated Please use getDeliveryOrder(Long, String) method
     */
    @Deprecated
    @Transactional(readOnly = true)
    public DeliveryOrderDTO getDeliveryOrder(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .map(DeliveryOrderDTO::new)
                .orElse(null);
    }

    /**
     * Get all delivery tasks for a customer by email
     * @param email Customer email
     * @return List of delivery task DTOs
     */
    @Transactional(readOnly = true)
    public List<DeliveryOrderDTO> getAllDeliveryOrders(String email) {
        // Fetch deliveries ordered by ID descending so newest deliveries appear first
        List<Delivery> deliveries = deliveryRepository.findByEmailOrderByIdDesc(email);

        // Convert entity list to DTO list
        return deliveries.stream()
            .map(DeliveryOrderDTO::new)
            .collect(Collectors.toList());
    }

    /** What a cancel-by-order did (the cancel API table in the design) */
    public enum CancelOutcome { CANCELLED_BEFORE_CREATED, CANCELLED, ALREADY_CANCELLED, TOO_LATE }

    /** @param status the delivery's status after the call (for TOO_LATE: how far it already got) */
    public record CancelResult(CancelOutcome outcome, DeliveryStatus status) {}

    /**
     * Cancel by order id: store's POST /internal/deliveries/cancel-by-order/{orderId}, and OrderCancelled.
     *
     *   no row yet         → insert a cancel marker (status CANCELLED) + Cancelled → CANCELLED_BEFORE_CREATED
     *   CREATED            → CANCELLED + Cancelled                                  → CANCELLED
     *   already CANCELLED  → nothing                                                → ALREADY_CANCELLED
     *   PICKED_UP or later → nothing                                                → TOO_LATE
     *
     * First INSERT ... ON CONFLICT DO NOTHING, then SELECT ... FOR UPDATE. If OrderPaid is creating the row at the
     * same moment, one insert waits for the other's commit; whichever comes second finds the row. Both ways end
     * with one row, CANCELLED (B7). The row lock also beats a worker step in progress: the worker's save then
     * fails its @Version check and its event rolls back with it.
     */
    @Transactional
    public CancelResult cancelByOrderId(String orderId) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = jdbc.update("""
                INSERT INTO delivery (version, order_id, delivery_status, quantity, creation_time, update_time, retry_count)
                VALUES (0, ?, 'CANCELLED', 0, ?, ?, 0)
                ON CONFLICT (order_id) DO NOTHING""", orderId, now, now);
        Delivery d = deliveryRepository.findByOrderIdForUpdate(orderId).orElseThrow();
        if (inserted == 1) {
            logger.info("Order {} cancelled before its delivery existed: cancel marker {} inserted", orderId, d.getId());
            eventRecorder.recordCancelMarker(d);
            return new CancelResult(CancelOutcome.CANCELLED_BEFORE_CREATED, DeliveryStatus.CANCELLED);
        }
        switch (d.getDeliveryStatus()) {
            case CREATED -> {
                d.setDeliveryStatus(DeliveryStatus.CANCELLED);
                d.setUpdateTime(now);
                Delivery saved = deliveryRepository.save(d);
                eventRecorder.recordStatus(saved);
                logger.info("Delivery {} for order {} cancelled", saved.getId(), orderId);
                return new CancelResult(CancelOutcome.CANCELLED, DeliveryStatus.CANCELLED);
            }
            case CANCELLED -> {
                return new CancelResult(CancelOutcome.ALREADY_CANCELLED, DeliveryStatus.CANCELLED);
            }
            default -> {
                return new CancelResult(CancelOutcome.TOO_LATE, d.getDeliveryStatus());
            }
        }
    }
}
