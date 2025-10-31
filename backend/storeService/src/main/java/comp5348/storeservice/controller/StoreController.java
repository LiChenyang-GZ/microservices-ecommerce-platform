package comp5348.storeservice.controller;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.service.OrderProductService;
import comp5348.storeservice.service.ProductService;
import comp5348.storeservice.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/store")
@CrossOrigin(origins = "*")
public class StoreController {
    
    private static final Logger logger = LoggerFactory.getLogger(StoreController.class);
    
    @Autowired
    private OrderProductService orderProductService;
    
    @Autowired
    private ProductService productService;
    
    @Autowired
    private OrderService orderService;
    
    /**
     * 創建訂單並處理支付
     * POST /api/store/orders/create-with-payment
     */
    @PostMapping("/orders/create-with-payment")
    public ResponseEntity<OrderResponse> createOrderWithPayment(@RequestBody CreateOrderRequest request) {
        logger.info("POST /api/store/orders/create-with-payment - Creating order with payment for user: {}", 
                   request.getUserId());
        
        try {
            OrderDTO order = orderProductService.createOrderWithPayment(request);
            return ResponseEntity.ok(OrderResponse.success(order, "Order created with payment successfully"));
        } catch (Exception e) {
            logger.error("Error creating order with payment: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to create order with payment: " + e.getMessage()));
        }
    }
    
    /**
     * 獲取訂單的完整資訊（包含支付狀態）
     * GET /api/store/orders/{id}/with-payment
     */
    @GetMapping("/orders/{id}/with-payment")
    public ResponseEntity<OrderWithPaymentDTO> getOrderWithPaymentInfo(@PathVariable Long id) {
        logger.info("GET /api/store/orders/{}/with-payment - Getting order with payment info", id);
        
        try {
            OrderWithPaymentDTO orderWithPayment = orderProductService.getOrderWithPaymentInfo(id);
            return ResponseEntity.ok(orderWithPayment);
        } catch (Exception e) {
            logger.error("Error getting order with payment info for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * 獲取用戶的訂單列表（包含支付狀態）
     * GET /api/store/orders/user/{userId}/with-payment
     */
    @GetMapping("/orders/user/{userId}/with-payment")
    public ResponseEntity<List<OrderWithPaymentDTO>> getUserOrdersWithPaymentInfo(@PathVariable Long userId) {
        logger.info("GET /api/store/orders/user/{}/with-payment - Getting user orders with payment info", userId);
        
        try {
            List<OrderWithPaymentDTO> orders = orderProductService.getUserOrdersWithPaymentInfo(userId);
            return ResponseEntity.ok(orders);
        } catch (Exception e) {
            logger.error("Error getting user orders with payment info for {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 取消訂單並處理退款
     * PUT /api/store/orders/{id}/cancel-with-refund
     */
    @PutMapping("/orders/{id}/cancel-with-refund")
    public ResponseEntity<OrderResponse> cancelOrderWithRefund(@PathVariable Long id, 
                                                               @RequestBody(required = false) String reason) {
        logger.info("PUT /api/store/orders/{}/cancel-with-refund - Cancelling order with refund", id);
        
        try {
            String refundReason = (reason != null && !reason.isEmpty()) ? reason : "Customer requested refund";
            OrderDTO order = orderProductService.cancelOrderWithRefund(id, refundReason);
            return ResponseEntity.ok(OrderResponse.success(order, "Order cancelled with refund processed"));
        } catch (Exception e) {
            logger.error("Error cancelling order with refund for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to cancel order with refund: " + e.getMessage()));
        }
    }
    
    /**
     * 獲取商品庫存資訊
     * GET /api/store/products/{id}/stock
     */
    @GetMapping("/products/{id}/stock")
    public ResponseEntity<ProductStockDTO> getProductStockInfo(@PathVariable Long id) {
        logger.info("GET /api/store/products/{}/stock - Getting product stock info", id);
        
        try {
            ProductStockDTO stockInfo = orderProductService.getProductStockInfo(id);
            return ResponseEntity.ok(stockInfo);
        } catch (Exception e) {
            logger.error("Error getting product stock info for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * 更新商品庫存
     * PUT /api/store/products/{id}/stock
     */
    @PutMapping("/products/{id}/stock")
    public ResponseEntity<ProductResponse> updateProductStock(@PathVariable Long id, 
                                                              @RequestParam Integer stock) {
        logger.info("PUT /api/store/products/{}/stock - Deprecated endpoint called", id);
        return ResponseEntity.badRequest()
                .body(ProductResponse.error("Deprecated: update stock via warehouse endpoints"));
    }
    
    /**
     * 處理支付成功
     * POST /api/store/orders/{id}/payment-success
     */
    @PostMapping("/orders/{id}/payment-success")
    public ResponseEntity<OrderResponse> processPaymentSuccess(@PathVariable Long id) {
        logger.info("POST /api/store/orders/{}/payment-success - Processing payment success", id);
        
        try {
            OrderDTO order = orderProductService.processPaymentSuccess(id);
            return ResponseEntity.ok(OrderResponse.success(order, "Payment success processed"));
        } catch (Exception e) {
            logger.error("Error processing payment success for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to process payment success: " + e.getMessage()));
        }
    }

    /**
     * 取消订单（仅在配送未取件前允许）
     * POST /api/store/orders/{id}/cancel
     */
    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable Long id) {
        logger.info("POST /api/store/orders/{}/cancel - Cancelling order", id);
        try {
            OrderDTO order = orderService.cancelOrder(id);
            return ResponseEntity.ok(OrderResponse.success(order, "Order cancelled successfully"));
        } catch (Exception e) {
            logger.error("Error cancelling order {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to cancel order: " + e.getMessage()));
        }
    }
    
    /**
     * 處理支付失敗
     * POST /api/store/orders/{id}/payment-failure
     */
    @PostMapping("/orders/{id}/payment-failure")
    public ResponseEntity<OrderResponse> processPaymentFailure(@PathVariable Long id) {
        logger.info("POST /api/store/orders/{}/payment-failure - Processing payment failure", id);
        
        try {
            OrderDTO order = orderProductService.processPaymentFailure(id);
            return ResponseEntity.ok(OrderResponse.success(order, "Payment failure processed"));
        } catch (Exception e) {
            logger.error("Error processing payment failure for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to process payment failure: " + e.getMessage()));
        }
    }
}
