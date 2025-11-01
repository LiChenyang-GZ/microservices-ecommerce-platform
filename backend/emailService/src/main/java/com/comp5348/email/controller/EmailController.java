package com.comp5348.email.controller;

import com.comp5348.email.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/email")
@CrossOrigin(origins = "http://localhost:3000")
public class EmailController {

    @Autowired
    private EmailService emailService;

    /**
     * Send registration verification code
     */
    @PostMapping("/send-verification")
    public ResponseEntity<?> sendVerificationCode(@RequestBody SendVerificationRequest request) {
        try {
            String code = emailService.sendRegistrationVerificationCode(request.email);
            return ResponseEntity.ok(new SendVerificationResponse(true, "Verification code has been sent to your email"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SendVerificationResponse(false, "Sending failed: " + e.getMessage()));
        }
    }

    /**
     * Send delivery status update notification
     */
    @PostMapping("/send-delivery-update")
    public ResponseEntity<?> sendDeliveryUpdate(@RequestBody DeliveryUpdateRequest request) {
        try {
            emailService.sendDeliveryStatusEmail(
                    request.email,
                    request.orderId,
                    request.deliveryId,
                    request.status
            );
            return ResponseEntity.ok(new SimpleResponse(true, "Notification email has been sent"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SimpleResponse(false, "Sending failed: " + e.getMessage()));
        }
    }

    /**
     * Send order cancellation notification
     */
    @PostMapping("/send-order-cancelled")
    public ResponseEntity<?> sendOrderCancelled(@RequestBody OrderNotifyRequest request) {
        try {
            emailService.sendOrderCancelledEmail(request.email, request.orderId, request.reason);
            return ResponseEntity.ok(new SimpleResponse(true, "Cancellation email has been sent"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SimpleResponse(false, "Sending failed: " + e.getMessage()));
        }
    }

    /**
     * Send order processing failure notification
     */
    @PostMapping("/send-order-failed")
    public ResponseEntity<?> sendOrderFailed(@RequestBody OrderNotifyRequest request) {
        try {
            emailService.sendOrderFailedEmail(request.email, request.orderId, request.reason);
            return ResponseEntity.ok(new SimpleResponse(true, "Failure notification email has been sent"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SimpleResponse(false, "Sending failed: " + e.getMessage()));
        }
    }

    /**
     * Send refund success notification
     */
    @PostMapping("/send-refund-success")
    public ResponseEntity<?> sendRefundSuccess(@RequestBody RefundNotifyRequest request) {
        try {
            emailService.sendRefundSuccessEmail(request.email, request.orderId, request.refundTxnId);
            return ResponseEntity.ok(new SimpleResponse(true, "Refund success email has been sent"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SimpleResponse(false, "Sending failed: " + e.getMessage()));
        }
    }

    /**
     * Verify verification code
     */
    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(@RequestBody VerifyCodeRequest request) {
        try {
            boolean isValid = emailService.verifyCode(request.email, request.code);
            if (isValid) {
                return ResponseEntity.ok(new VerifyCodeResponse(true, "Verification successful"));
            } else {
                return ResponseEntity.badRequest().body(new VerifyCodeResponse(false, "Verification code is invalid or expired"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new VerifyCodeResponse(false, "Verification failed: " + e.getMessage()));
        }
    }

    /**
     * Check if email is verified
     */
    @GetMapping("/check-verified/{email}")
    public ResponseEntity<?> checkEmailVerified(@PathVariable String email) {
        try {
            boolean isVerified = emailService.isEmailVerified(email);
            return ResponseEntity.ok(new EmailVerifiedResponse(isVerified));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new EmailVerifiedResponse(false));
        }
    }

    /**
     * Send password reset verification code
     */
    @PostMapping("/send-password-reset")
    public ResponseEntity<?> sendPasswordResetCode(@RequestBody SendPasswordResetRequest request) {
        try {
            String code = emailService.sendPasswordResetCode(request.email);
            return ResponseEntity.ok(new SendPasswordResetResponse(true, "Password reset verification code has been sent to your email"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SendPasswordResetResponse(false, "Sending failed: " + e.getMessage()));
        }
    }

    /**
     * Verify password reset verification code
     */
    @PostMapping("/verify-password-reset")
    public ResponseEntity<?> verifyPasswordResetCode(@RequestBody VerifyPasswordResetRequest request) {
        try {
            boolean isValid = emailService.verifyPasswordResetCode(request.email, request.code);
            if (isValid) {
                return ResponseEntity.ok(new VerifyPasswordResetResponse(true, "Verification successful"));
            } else {
                return ResponseEntity.badRequest().body(new VerifyPasswordResetResponse(false, "Verification code is invalid or expired"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new VerifyPasswordResetResponse(false, "Verification failed: " + e.getMessage()));
        }
    }

    // Request and response classes
    public static class SendVerificationRequest {
        public String email;
    }

    public static class SendVerificationResponse {
        public boolean success;
        public String message;

        public SendVerificationResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class VerifyCodeRequest {
        public String email;
        public String code;
    }

    public static class VerifyCodeResponse {
        public boolean success;
        public String message;

        public VerifyCodeResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class EmailVerifiedResponse {
        public boolean verified;

        public EmailVerifiedResponse(boolean verified) {
            this.verified = verified;
        }
    }

    public static class SendPasswordResetRequest {
        public String email;
    }

    public static class SendPasswordResetResponse {
        public boolean success;
        public String message;

        public SendPasswordResetResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class VerifyPasswordResetRequest {
        public String email;
        public String code;
    }

    public static class VerifyPasswordResetResponse {
        public boolean success;
        public String message;

        public VerifyPasswordResetResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // =============== Delivery Update DTOs ===============
    public static class DeliveryUpdateRequest {
        public String email;
        public String orderId;
        public Long deliveryId;
        public String status;
    }

    public static class SimpleResponse {
        public boolean success;
        public String message;

        public SimpleResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class OrderNotifyRequest {
        public String email;
        public String orderId;
        public String reason;
    }

    public static class RefundNotifyRequest {
        public String email;
        public String orderId;
        public String refundTxnId;
    }
}
