package comp5348.storeservice.controller;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Only the admin dashboard's order list is left here. The rest of this controller (read any order or any user's
 * orders, create an order without payment, set any status, a second cancel endpoint, an order's stock records)
 * had no caller and checked no owner, so auth PR 2 removed it (decision 3). Users read and change their own
 * orders through StoreController.
 */
@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    @Autowired
    private OrderService orderService;

    /**
     * Get all order list (admin dashboard). Needs a login since auth PR 2; the admin role comes in PR 3.
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
}
