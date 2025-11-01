package comp5348.storeservice.service;

import comp5348.storeservice.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CompensationService {

    private static final Logger logger = LoggerFactory.getLogger(CompensationService.class);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    /**
     * Handle compensation for delivery creation failure: refund + set order to system cancelled
     */
    public boolean compensateDeliveryFailed(Long orderId, String reason) {
        try {
            String refundReason = (reason != null && !reason.isEmpty()) ? reason : "Delivery service failed to create shipment.";
            paymentService.refundPayment(orderId, refundReason);
            orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED_SYSTEM);
            logger.info("Compensation completed for orderId={}, reason={}", orderId, refundReason);
            return true;
        } catch (Exception e) {
            logger.error("Compensation failed for orderId {}: {}", orderId, e.getMessage(), e);
            return false;
        }
    }
}


