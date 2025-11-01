package com.comp5348.delivery.controller;

import com.comp5348.delivery.dto.DeliveryOrderDTO;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.service.DeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController // Tells Spring this is a RESTful API controller
@RequestMapping("/api/deliveries") // Defines URL prefix for all APIs under this controller
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class DeliveryController {

    private final DeliveryService deliveryService;

    // Inject Service through constructor
    @Autowired
    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * API endpoint to create delivery task
     * @param request JSON request body containing delivery information
     * @return Response containing created task information
     */
    @PostMapping("/create") // Responds to POST request, URL is /api/deliveries/create
    public ResponseEntity<DeliveryOrderDTO> createDelivery(
            @RequestBody DeliveryRequest request // @RequestBody tells Spring to automatically convert JSON content to DeliveryRequest object
    ) {
        // Call Service layer to handle business logic
        DeliveryOrderDTO createdOrder = deliveryService.createDelivery(request);

        // Return HTTP 201 Created response with created order information in response body
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrder);
    }

    /**
     * API endpoint to create delivery tasks in batch
     */
    @PostMapping("/create/batch")
    public ResponseEntity<List<DeliveryOrderDTO>> createDeliveryBatch(@RequestBody List<DeliveryRequest> requests) {
        List<DeliveryOrderDTO> createdOrders = deliveryService.createDeliveryBatch(requests);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrders);
    }

    /**
     * API endpoint to get single delivery task by ID (can only get own orders)
     */
    @GetMapping("/{deliveryId}")
    public ResponseEntity<?> getDeliveryOrder(
            @PathVariable Long deliveryId,
            HttpServletRequest request) {
        // Get user email from request attribute set by filter
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        DeliveryOrderDTO deliveryOrder = deliveryService.getDeliveryOrder(deliveryId, userEmail);
        if (deliveryOrder != null) {
            return ResponseEntity.ok(deliveryOrder);
        } else {
            // Return 404 or 403 based on business needs. Here returns 404 to avoid leaking whether order exists
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Delivery order not found or you don't have permission to access it");
        }
    }

    /**
     * Get all delivery tasks for currently logged in user (no longer requires email parameter)
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyDeliveryOrders(HttpServletRequest request) {
        // Get user email from request attribute set by filter
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        List<DeliveryOrderDTO> deliveryOrders = deliveryService.getAllDeliveryOrders(userEmail);
        return ResponseEntity.ok(deliveryOrders);
    }

    /**
     * Internal call: Cancel delivery by order ID (only CREATED can be cancelled)
     */
    @PostMapping("/cancel-by-order/{orderId}")
    public ResponseEntity<?> cancelByOrderId(@PathVariable String orderId) {
        boolean ok = deliveryService.cancelByOrderId(orderId);
        if (ok) return ResponseEntity.ok().build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Cannot cancel after pickup or delivery not found");
    }

    /**
     * Compatible with old API endpoint (deprecated, kept for backward compatibility)
     * @deprecated Please use GET /api/deliveries/me
     */
    @Deprecated
    @GetMapping("/all/{email}")
    public ResponseEntity<?> getAllDeliveryOrders(
            @PathVariable String email,
            HttpServletRequest request) {
        // Get user email from request attribute set by filter
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        // Verify: can only query own orders
        if (!userEmail.equals(email)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: You can only view your own delivery orders");
        }
        
        List<DeliveryOrderDTO> deliveryOrders = deliveryService.getAllDeliveryOrders(userEmail);
        return ResponseEntity.ok(deliveryOrders);
    }

    /**
     * Cancel delivery task (can only cancel own orders)
     */
    @PostMapping("/{deliveryId}/cancel")
    public ResponseEntity<String> cancelDelivery(
            @PathVariable Long deliveryId,
            HttpServletRequest request) {
        // Get user email from request attribute set by filter
        String userEmail = (String) request.getAttribute("userEmail");
        
        if (userEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Unauthorized: Please login first");
        }
        
        boolean success = deliveryService.cancelDelivery(deliveryId, userEmail);

        if (success) {
            return ResponseEntity.ok("Delivery " + deliveryId + " has been cancelled successfully.");
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Delivery " + deliveryId + " could not be cancelled. It might not belong to you, be in an invalid state, or already be delivering.");
        }
    }
}

