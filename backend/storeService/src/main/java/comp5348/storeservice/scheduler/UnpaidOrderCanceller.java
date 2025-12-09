package comp5348.storeservice.scheduler;

import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled task to auto-cancel unpaid orders after 15 minutes
 */
@Component
public class UnpaidOrderCanceller {

    private static final Logger logger = LoggerFactory.getLogger(UnpaidOrderCanceller.class);
    private static final int PAYMENT_TIMEOUT_MINUTES = 15;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    /**
     * Run every minute to check for expired unpaid orders
     */
    @Scheduled(fixedRate = 60000) // Run every 60 seconds
    public void cancelExpiredUnpaidOrders() {
        try {
            // Find all PENDING_PAYMENT orders
            List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.PENDING_PAYMENT);

            if (pendingOrders.isEmpty()) {
                return;
            }

            logger.info("Checking {} pending payment orders for expiration", pendingOrders.size());

            LocalDateTime now = LocalDateTime.now();
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

                            orderService.cancelOrder(order.getId());
                            cancelledCount++;

                            logger.info("Successfully cancelled expired order: {}", order.getId());
                        } catch (Exception e) {
                            logger.error("Failed to cancel expired order {}: {}", order.getId(), e.getMessage(), e);
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
}