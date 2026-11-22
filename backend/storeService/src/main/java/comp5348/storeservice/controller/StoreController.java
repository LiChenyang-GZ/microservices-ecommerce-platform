package comp5348.storeservice.controller;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.exception.NotYourOrdersException;
import comp5348.storeservice.exception.OrderNotFoundException;
import comp5348.storeservice.service.OrderProductService;
import comp5348.storeservice.service.ProductService;
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
    
    /**
     * Create order and process payment
     * POST /api/store/orders/create-with-payment
     */
    @PostMapping("/orders/create-with-payment")
    public ResponseEntity<OrderResponse> createOrderWithPayment(@Valid @RequestBody CreateOrderRequest request,
                                                                HttpServletRequest http) {
        logger.info("POST /api/store/orders/create-with-payment - Creating order with payment");

        try {
            // The order is the logged-in user's; a userId in the body is ignored
            OrderDTO order = orderProductService.createOrderWithPaymentFor(userEmail(http), request);
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
    public ResponseEntity<OrderWithPaymentDTO> getOrderWithPaymentInfo(@PathVariable Long id, HttpServletRequest http) {
        logger.info("GET /api/store/orders/{}/with-payment - Getting order with payment info", id);

        try {
            OrderWithPaymentDTO orderWithPayment = orderProductService.getOrderWithPaymentInfo(id, userEmail(http));
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
    public ResponseEntity<List<OrderWithPaymentDTO>> getUserOrdersWithPaymentInfo(@PathVariable Long userId,
                                                                                   HttpServletRequest http) {
        logger.info("GET /api/store/orders/user/{}/with-payment - Getting user orders with payment info", userId);

        try {
            List<OrderWithPaymentDTO> orders = orderProductService.getUserOrdersWithPaymentInfo(userId, userEmail(http));
            return ResponseEntity.ok(orders);
        } catch (NotYourOrdersException e) {
            return ResponseEntity.status(403).build();
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
     * Retry payment for an existing order with PENDING_PAYMENT status
     * POST /api/store/orders/{id}/retry-payment
     */
    @PostMapping("/orders/{id}/retry-payment")
    public ResponseEntity<OrderResponse> retryPayment(@PathVariable Long id, HttpServletRequest request) {
        logger.info("POST /api/store/orders/{}/retry-payment - Retrying payment for order", id);

        try {
            // Pay Now only queues the charge; the page polls the payment status until it settles
            OrderDTO order = orderProductService.retryOrderPayment(id, userEmail(request));
            String message = "PAID".equals(order.getStatus()) ? "Order already paid" : "Payment processing";
            return ResponseEntity.ok(OrderResponse.success(order, message));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(OrderResponse.error(e.getMessage()));
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
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable Long id, HttpServletRequest http) {
        logger.info("POST /api/store/orders/{}/cancel - Cancelling order", id);
        try {
            OrderDTO order = orderProductService.cancelOwnOrder(id, userEmail(http));
            return ResponseEntity.ok(OrderResponse.success(order, "Order cancelled successfully"));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(OrderResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error cancelling order {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to cancel order: " + e.getMessage()));
        }
    }

    /** Set by JwtAuthenticationFilter; SecurityConfig lets no request without a valid token reach these methods */
    private static String userEmail(HttpServletRequest http) {
        return (String) http.getAttribute("userEmail");
    }
}
