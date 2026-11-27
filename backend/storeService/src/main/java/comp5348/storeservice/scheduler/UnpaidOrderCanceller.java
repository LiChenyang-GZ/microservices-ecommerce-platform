package comp5348.storeservice.scheduler;

import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Scheduled task to auto-cancel unpaid orders after 15 minutes. This is also where the stock of an order whose
 * payment failed comes back: bank's "no" leaves the order PENDING_PAYMENT for Pay Now, and this cancel releases it.
 *
 * A cancel that fails rolls back whole (order and stock untouched) and is tried again next minute: right for a
 * transient error. For one that fails every time (bad data) the stock would stay held for good with only an ERROR
 * a minute, so after stuckAfterFailures failures in a row for the same order: one ALERT unpaid_cancel_stuck
 * (review 4b #7, Obsidian 87). The count lives in memory: a restart starts it again (and may alert once more).
 */
@Component
public class UnpaidOrderCanceller {

    private static final Logger logger = LoggerFactory.getLogger(UnpaidOrderCanceller.class);
    private static final int PAYMENT_TIMEOUT_MINUTES = 15;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private Clock clock;

    @Value("${unpaid-cancel.stuck-after-failures:5}")
    private int stuckAfterFailures = 5;

    /** Failed cancels in a row, per order id. Only this scheduler's thread writes it */
    private final Map<Long, Integer> failuresInARow = new ConcurrentHashMap<>();

    /**
     * Run every minute to check for expired unpaid orders
     */
    @Scheduled(fixedRate = 60000) // Run every 60 seconds
    public void cancelExpiredUnpaidOrders() {
        try {
            // Find all PENDING_PAYMENT orders
            List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.PENDING_PAYMENT);

            // Orders that left PENDING_PAYMENT some other way (paid, cancelled by the user) are not tracked any more
            Set<Long> pendingIds = pendingOrders.stream().map(Order::getId).collect(Collectors.toSet());
            failuresInARow.keySet().retainAll(pendingIds);
            if (pendingOrders.isEmpty()) {
                return;
            }

            logger.info("Checking {} pending payment orders for expiration", pendingOrders.size());

            LocalDateTime now = LocalDateTime.now(clock);
            int cancelledCount = 0;

            for (Order order : pendingOrders) {
                // Check if order has been pending for more than 15 minutes
                LocalDateTime createdAt = order.getCreatedAt();
                if (createdAt != null) {
                    LocalDateTime expirationTime = createdAt.plusMinutes(PAYMENT_TIMEOUT_MINUTES);

                    if (now.isAfter(expirationTime)) {
                        try {
                            logger.info("Auto-cancelling expired unpaid order: orderId={}, createdAt={}",
                                    order.getId(), createdAt);

                            // Re-checks the status under a row lock: the list above may be stale
                            if (orderService.cancelIfStillUnpaid(order.getId())) {
                                cancelledCount++;
                                logger.info("Successfully cancelled expired order: {}", order.getId());
                            }
                            failuresInARow.remove(order.getId());
                        } catch (Exception e) {
                            logger.error("Failed to cancel expired order {}: {}", order.getId(), e.getMessage(), e);
                            noteFailure(order.getId(), e);
                        }
                    }
                }
            }

            if (cancelledCount > 0) {
                logger.info("Auto-cancelled {} expired unpaid orders", cancelledCount);
            }

        } catch (Exception e) {
            logger.error("Error in unpaid order cancellation task: {}", e.getMessage(), e);
        }
    }

    private void noteFailure(Long orderId, Exception e) {
        int failures = failuresInARow.merge(orderId, 1, Integer::sum);
        if (failures == stuckAfterFailures) {
            logger.error("ALERT unpaid_cancel_stuck orderId={} failures={} lastError={} (the order stays "
                            + "PENDING_PAYMENT and its stock stays held until this cancel goes through)",
                    orderId, failures, oneLine(NestedExceptionUtils.getMostSpecificCause(e).getMessage()));
        }
    }

    private static String oneLine(String message) {
        return message == null ? null : message.replaceAll("\\s*\\R\\s*", " ");
    }
}