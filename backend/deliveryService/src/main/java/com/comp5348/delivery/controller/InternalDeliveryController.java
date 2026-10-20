package com.comp5348.delivery.controller;

import com.comp5348.delivery.service.DeliveryService;
import com.comp5348.delivery.service.DeliveryService.CancelOutcome;
import com.comp5348.delivery.service.DeliveryService.CancelResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Service-to-service calls (store → delivery). Not under /api/*, so the user-token filter does not apply.
 * No authentication either, same as the endpoint it replaces (Christy's choice on question 2: K8s does not route
 * /internal from outside; service-to-service auth stays on the security to-do list).
 */
@RestController
@RequestMapping("/internal/deliveries")
public class InternalDeliveryController {

    private final DeliveryService deliveryService;

    public InternalDeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * Store asks before cancelling a paid order (cancel API table in the design):
     *   200 {"result":"CANCELLED_BEFORE_CREATED" | "CANCELLED" | "ALREADY_CANCELLED"} → delivery will not ship it
     *   409 {"result":"TOO_LATE","status":"PICKED_UP" ...}                              → already on its way
     */
    @PostMapping("/cancel-by-order/{orderId}")
    public ResponseEntity<Map<String, String>> cancelByOrder(@PathVariable String orderId) {
        CancelResult result = deliveryService.cancelByOrderId(orderId);
        if (result.outcome() == CancelOutcome.TOO_LATE) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("result", result.outcome().name(), "status", result.status().name()));
        }
        return ResponseEntity.ok(Map.of("result", result.outcome().name()));
    }
}
