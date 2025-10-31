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
     * 接收来自 DeliveryService 的状态更新通知
     */
    @PostMapping
    public ResponseEntity<Void> receiveDeliveryUpdate(@RequestBody DeliveryNotificationDTO notification) {
        logger.info("Received delivery notification for orderId={}: status={}",
                notification.getDeliveryId(), notification.getStatus());

        try {
            // 将通知转发给 OrderService 进行处理
            orderService.handleDeliveryUpdate(notification);

            // 返回 200 OK 表示我们已成功接收并处理
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            // 如果在处理过程中发生错误（比如找不到订单），记录错误并返回一个服务器错误码
            // 这会让 DeliveryService 知道它的通知可能没有被成功处理，可能会触发重试
            logger.error("Error processing delivery notification for orderId={}: {}",
                    notification.getDeliveryId(), e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
