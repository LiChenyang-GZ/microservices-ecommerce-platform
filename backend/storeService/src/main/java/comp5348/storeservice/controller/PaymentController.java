package comp5348.storeservice.controller;

import comp5348.storeservice.dto.PaymentResponse;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "*")
public class PaymentController {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);
    
    @Autowired
    private PaymentService paymentService;
    
    /**
     * Request refund. Needs a login since auth PR 2; PR 3 makes it admin-only (decision 4).
     * Creating a payment with any amount and reading any payment by id had no caller and were removed (decision 3).
     */
    @PostMapping("/{orderId}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long orderId, @RequestBody(required = false) String reason) {
        logger.info("Processing refund for orderId={}, reason={}", orderId, reason);
        
        try {
            String refundReason = (reason != null && !reason.isEmpty()) ? reason : "Customer requested refund";
            // Writes a REFUND task; the refund itself happens in the background (status REFUND_PENDING → REFUNDED)
            Payment payment = paymentService.requestRefund(orderId, refundReason);

            return ResponseEntity.ok(new PaymentResponse(true, payment, "Refund requested"));
            
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


