package com.comp5348.email.controller;

import com.comp5348.email.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * email's HTTP side. Public: send-verification and verify-code (the registration page). Service token
 * (ServiceTokenFilter): send-password-reset and verify-password-reset, called only by store's AccountService.
 * Notification emails do not come through here: they go over RabbitMQ (email_queue → EmailQueueConsumer).
 * The old send-delivery-update / send-order-cancelled / send-order-failed / send-refund-success and
 * check-verified had no callers and were removed (Christy's decision 3, 2026-11-17).
 */
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
}
