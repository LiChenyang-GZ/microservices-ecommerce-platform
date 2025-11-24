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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

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
     * Create order and process payment
     * POST /api/store/orders/create-with-payment
     */
    @PostMapping("/orders/create-with-payment")
    public ResponseEntity<OrderResponse> createOrderWithPayment(@Valid @RequestBody CreateOrderRequest request) {
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
     * Get complete order information (including payment status)
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
     * Get user's order list (including payment status)
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
     * Get product stock information
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
     * Process payment success
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
     * Retry payment for an existing order with PENDING_PAYMENT status
     * POST /api/store/orders/{id}/retry-payment
     */
    @PostMapping("/orders/{id}/retry-payment")
    public ResponseEntity<OrderResponse> retryPayment(@PathVariable Long id, HttpServletRequest request) {
        logger.info("POST /api/store/orders/{}/retry-payment - Retrying payment for order", id);

        String userEmail = (String) request.getAttribute("userEmail");
        if (userEmail == null || userEmail.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(OrderResponse.error("User not authenticated"));
        }

        try {
            OrderDTO order = orderProductService.retryOrderPayment(id, userEmail);
            return ResponseEntity.ok(OrderResponse.success(order, "Payment processed successfully"));
        } catch (Exception e) {
            logger.error("Error retrying payment for order {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Payment failed: " + e.getMessage()));
        }
    }

    /**
     * Cancel order (only allowed before delivery pickup)
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
     * Process payment failure
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
