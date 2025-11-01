package comp5348.storeservice.controller;

import comp5348.storeservice.dto.PaymentRequest;
import comp5348.storeservice.dto.PaymentResponse;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "*")
public class PaymentController {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);
    
    @Autowired
    private PaymentService paymentService;
    
    /**
     * Create payment
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@RequestBody PaymentRequest request) {
        logger.info("Creating payment: orderId={}, amount={}", request.getOrderId(), request.getAmount());
        
        try {
            Payment payment = paymentService.createPayment(request.getOrderId(), request.getAmount());
            return ResponseEntity.ok(new PaymentResponse(true, payment, "Payment created successfully"));
            
        } catch (Exception e) {
            logger.error("Failed to create payment: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(
                new PaymentResponse(false, null, "Failed to create payment: " + e.getMessage())
            );
        }
    }
    
    /**
     * Query payment status (by orderId)
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse> getPaymentByOrderId(@PathVariable Long orderId) {
        logger.info("Querying payment for orderId={}", orderId);
        
        Optional<Payment> payment = paymentService.getPaymentByOrderId(orderId);
        
        if (payment.isPresent()) {
            return ResponseEntity.ok(new PaymentResponse(true, payment.get(), "Payment found"));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Query payment status (by paymentId)
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentById(@PathVariable Long paymentId) {
        logger.info("Querying payment: paymentId={}", paymentId);
        
        Optional<Payment> payment = paymentService.getPaymentById(paymentId);
        
        if (payment.isPresent()) {
            return ResponseEntity.ok(new PaymentResponse(true, payment.get(), "Payment found"));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Request refund
     */
    @PostMapping("/{orderId}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long orderId, @RequestBody(required = false) String reason) {
        logger.info("Processing refund for orderId={}, reason={}", orderId, reason);
        
        try {
            String refundReason = (reason != null && !reason.isEmpty()) ? reason : "Customer requested refund";
            Payment payment = paymentService.refundPayment(orderId, refundReason);
            
            return ResponseEntity.ok(new PaymentResponse(true, payment, "Refund processed successfully"));
            
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.error("Refund validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                new PaymentResponse(false, null, e.getMessage())
            );
            
        } catch (Exception e) {
            logger.error("Failed to process refund: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                new PaymentResponse(false, null, "Failed to process refund: " + e.getMessage())
            );
        }
    }
}


