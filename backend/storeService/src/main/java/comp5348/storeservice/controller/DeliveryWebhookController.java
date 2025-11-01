package comp5348.storeservice.controller;

import comp5348.storeservice.dto.DeliveryNotificationDTO;
import comp5348.storeservice.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/delivery-webhook")
public class DeliveryWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryWebhookController.class);

    @Autowired
    private OrderService orderService;

    /**
     * Receive status update notification from DeliveryService
     */
    @PostMapping
    public ResponseEntity<Void> receiveDeliveryUpdate(@RequestBody DeliveryNotificationDTO notification) {
        logger.info("Received delivery notification for orderId={}: status={}",
                notification.getDeliveryId(), notification.getStatus());

        try {
            // Forward notification to OrderService for processing
            orderService.handleDeliveryUpdate(notification);

            // Return 200 OK to indicate we successfully received and processed
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            // If an error occurs during processing (e.g., order not found), log error and return server error code
            // This will let DeliveryService know its notification may not have been processed successfully, may trigger retry
            logger.error("Error processing delivery notification for orderId={}: {}",
                    notification.getDeliveryId(), e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
