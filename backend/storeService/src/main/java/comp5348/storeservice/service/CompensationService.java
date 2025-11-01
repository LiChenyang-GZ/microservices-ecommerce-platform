package comp5348.storeservice.service;

import comp5348.storeservice.dto.UnholdProductRequest;
import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CompensationService {

    private static final Logger logger = LoggerFactory.getLogger(CompensationService.class);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private OrderRepository orderRepository;

    /**
     * Handle compensation for delivery creation failure: refund + release inventory + set order to system cancelled
     */
    @Transactional
    public boolean compensateDeliveryFailed(Long orderId, String reason) {
        try {
            logger.info("Starting compensation for orderId={}, reason={}", orderId, reason);
            
            // 1. Get order information
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            // 2. Release inventory (critical step - must release the reserved stock)
            try {
                if (order.getInventoryTransactionIds() != null && !order.getInventoryTransactionIds().isEmpty()) {
                    List<Long> txIds = Arrays.stream(order.getInventoryTransactionIds().split(","))
                            .filter(s -> s != null && !s.trim().isEmpty())
                            .map(Long::valueOf)
                            .collect(Collectors.toList());
                    if (!txIds.isEmpty()) {
                        UnholdProductRequest unhold = new UnholdProductRequest();
                        unhold.setInventoryTransactionIds(txIds);
                        boolean unholdSuccess = warehouseService.unholdProduct(unhold);
                        if (unholdSuccess) {
                            logger.info("Inventory released successfully for orderId={}", orderId);
                        } else {
                            logger.error("CRITICAL: Failed to release inventory for orderId={}", orderId);
                        }
                    }
                } else {
                    logger.warn("Order {} has no inventory transaction IDs to release", orderId);
                }
            } catch (Exception e) {
                logger.error("CRITICAL: Unhold inventory failed for order {}: {}", orderId, e.getMessage(), e);
                // Continue with refund and status update even if inventory release fails
            }

            // 3. Process refund
            String refundReason = (reason != null && !reason.isEmpty()) ? reason : "Delivery service failed to create shipment.";
            paymentService.refundPayment(orderId, refundReason);
            logger.info("Refund processed for orderId={}", orderId);

            // 4. Update order status to CANCELLED_SYSTEM
            orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED_SYSTEM);
            logger.info("Order status updated to CANCELLED_SYSTEM for orderId={}", orderId);

            logger.info("Compensation completed successfully for orderId={}, reason={}", orderId, refundReason);
            return true;
        } catch (Exception e) {
            logger.error("Compensation failed for orderId {}: {}", orderId, e.getMessage(), e);
            return false;
        }
    }
}


