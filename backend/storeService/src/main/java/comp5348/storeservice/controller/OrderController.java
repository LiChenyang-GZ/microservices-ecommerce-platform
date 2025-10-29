package comp5348.storeservice.controller;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    
    @Autowired
    private OrderService orderService;
    
    /**
     * 獲取所有訂單列表
     * GET /api/orders
     */
    @GetMapping
    public ResponseEntity<OrderResponse> getAllOrders() {
        logger.info("GET /api/orders - Fetching all orders");
        
        try {
            List<OrderDTO> orders = orderService.getAllOrders();
            return ResponseEntity.ok(OrderResponse.success(orders, "Orders retrieved successfully"));
        } catch (Exception e) {
            logger.error("Error fetching all orders: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(OrderResponse.error("Failed to fetch orders: " + e.getMessage()));
        }
    }
    
    /**
     * 根據用戶ID獲取訂單列表
     * GET /api/orders/user/{userId}
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<OrderResponse> getOrdersByUserId(@PathVariable Long userId) {
        logger.info("GET /api/orders/user/{} - Fetching orders for user", userId);
        
        try {
            List<OrderDTO> orders = orderService.getOrdersByUserId(userId);
            return ResponseEntity.ok(OrderResponse.success(orders, "User orders retrieved successfully"));
        } catch (Exception e) {
            logger.error("Error fetching orders for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(OrderResponse.error("Failed to fetch user orders: " + e.getMessage()));
        }

    }
    
    /**
     * 根據訂單ID獲取訂單詳情
     * GET /api/orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long id) {
        logger.info("GET /api/orders/{} - Fetching order by id", id);
        
        try {
            Optional<OrderDTO> order = orderService.getOrderById(id);
            if (order.isPresent()) {
                return ResponseEntity.ok(OrderResponse.success(order.get(), "Order found"));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error fetching order by id {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(OrderResponse.error("Failed to fetch order: " + e.getMessage()));
        }
    }
    
    /**
     * 根據訂單狀態獲取訂單列表
     * GET /api/orders/status/{status}
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<OrderResponse> getOrdersByStatus(@PathVariable String status) {
        logger.info("GET /api/orders/status/{} - Fetching orders by status", status);
        
        try {
            OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
            List<OrderDTO> orders = orderService.getOrdersByStatus(orderStatus);
            return ResponseEntity.ok(OrderResponse.success(orders, "Orders by status retrieved successfully"));
        } catch (IllegalArgumentException e) {
            logger.error("Invalid order status: {}", status);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Invalid order status: " + status));
        } catch (Exception e) {
            logger.error("Error fetching orders by status {}: {}", status, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(OrderResponse.error("Failed to fetch orders by status: " + e.getMessage()));
        }
    }
    
    /**
     * 創建新訂單
     * POST /api/orders
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        logger.info("POST /api/orders - Creating new order for user: {}", request.getUserId());
        
        try {
            OrderDTO order = orderService.createOrder(request);
            return ResponseEntity.ok(OrderResponse.success(order, "Order created successfully"));
        } catch (Exception e) {
            logger.error("Error creating order: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to create order: " + e.getMessage()));
        }
    }
    
    /**
     * 更新訂單狀態
     * PUT /api/orders/{id}/status
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(@PathVariable Long id, @RequestParam String status) {
        logger.info("PUT /api/orders/{}/status - Updating order status to {}", id, status);
        
        try {
            OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
            OrderDTO order = orderService.updateOrderStatus(id, orderStatus);
            return ResponseEntity.ok(OrderResponse.success(order, "Order status updated successfully"));
        } catch (IllegalArgumentException e) {
            logger.error("Invalid order status: {}", status);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Invalid order status: " + status));
        } catch (Exception e) {
            logger.error("Error updating order status for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to update order status: " + e.getMessage()));
        }
    }
    
    /**
     * 取消訂單
     * PUT /api/orders/{id}/cancel
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable Long id) {
        logger.info("PUT /api/orders/{}/cancel - Cancelling order", id);
        
        try {
            OrderDTO order = orderService.cancelOrder(id);
            return ResponseEntity.ok(OrderResponse.success(order, "Order cancelled successfully"));
        } catch (Exception e) {
            logger.error("Error cancelling order {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(OrderResponse.error("Failed to cancel order: " + e.getMessage()));
        }
    }
}
