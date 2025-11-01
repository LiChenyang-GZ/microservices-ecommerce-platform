package com.comp5348.email.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import com.comp5348.email.model.EmailOutbox;
import com.comp5348.email.repository.EmailOutboxRepository;
import java.util.Random;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;
    
    @Autowired
    private EmailOutboxRepository emailOutboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Generate 6-digit verification code
     */
    public String generateVerificationCode() {
        Random random = new Random();
        return String.format("%06d", random.nextInt(1000000));
    }

    /**
     * Send registration verification code
     */
    public String sendRegistrationVerificationCode(String email) {
        // Generate verification code
        String code = generateVerificationCode();
        
        // Save to database
        EmailOutbox emailOutbox = new EmailOutbox(email, code, "REGISTRATION");
        emailOutboxRepository.save(emailOutbox);
        
        // Send email
        sendVerificationCode(email, code, "Registration Verification Code");
        
        return code;
    }

    /**
     * Send verification code email
     */
    public void sendVerificationCode(String to, String code, String type) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Your " + type);
        
        String emailContent = String.format(
            "Hello!\n\n" +
            "Your %s is: %s\n\n" +
            "This verification code will expire in 5 minutes, please use it promptly.\n\n" +
            "If you did not request this verification code, please ignore this email.\n\n" +
            "Thank you!",
            type, code
        );
        
        message.setText(emailContent);
        
        try {
            mailSender.send(message);
            // Update status to SENT
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("SENT");
                emailOutboxRepository.save(emailOutbox);
            }
        } catch (Exception e) {
            // Update status to FAILED
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("FAILED");
                emailOutbox.setRetryCount(emailOutbox.getRetryCount() + 1);
                emailOutboxRepository.save(emailOutbox);
            }
            throw new RuntimeException("Email sending failed: " + e.getMessage());
        }
    }

    /**
     * Verify verification code
     */
    public boolean verifyCode(String email, String code) {
        EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(email, code, "SENT");
        
        if (emailOutbox == null) {
            return false;
        }
        
        // Check if expired
        if (emailOutbox.isExpired()) {
            emailOutbox.setStatus("EXPIRED");
            emailOutboxRepository.save(emailOutbox);
            return false;
        }
        
        // Verification successful, mark as verified
        emailOutbox.setStatus("VERIFIED");
        emailOutboxRepository.save(emailOutbox);
        
        return true;
    }

    /**
     * Check if email is verified
     */
    public boolean isEmailVerified(String email) {
        EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndStatus(email, "VERIFIED");
        return emailOutbox != null && !emailOutbox.isExpired();
    }

    /**
     * Send password reset verification code
     */
    public String sendPasswordResetCode(String email) {
        // Generate verification code
        String code = generateVerificationCode();
        
        // Save to database
        EmailOutbox emailOutbox = new EmailOutbox(email, code, "PASSWORD_RESET");
        emailOutboxRepository.save(emailOutbox);
        
        // Send email
        sendPasswordResetEmail(email, code);
        
        return code;
    }

    /**
     * Send password reset email
     */
    public void sendPasswordResetEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("Password Reset Verification Code");
        
        String emailContent = String.format(
            "Hello!\n\n" +
            "Your password reset verification code is: %s\n\n" +
            "This verification code will expire in 5 minutes, please use it promptly.\n\n" +
            "If you did not request a password reset, please ignore this email.\n\n" +
            "Thank you!",
            code
        );
        
        message.setText(emailContent);
        
        try {
            mailSender.send(message);
            // Update status to SENT
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("SENT");
                emailOutboxRepository.save(emailOutbox);
            }
        } catch (Exception e) {
            // Update status to FAILED
            EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(to, code, "PENDING");
            if (emailOutbox != null) {
                emailOutbox.setStatus("FAILED");
                emailOutbox.setRetryCount(emailOutbox.getRetryCount() + 1);
                emailOutboxRepository.save(emailOutbox);
            }
            throw new RuntimeException("Password reset email sending failed: " + e.getMessage());
        }
    }

    /**
     * Verify password reset verification code
     */
    public boolean verifyPasswordResetCode(String email, String code) {
        EmailOutbox emailOutbox = emailOutboxRepository.findByEmailAndCodeAndStatus(email, code, "SENT");
        
        if (emailOutbox == null) {
            return false;
        }
        
        // Check if expired
        if (emailOutbox.isExpired()) {
            emailOutbox.setStatus("EXPIRED");
            emailOutboxRepository.save(emailOutbox);
            return false;
        }
        
        // Verification successful, mark as verified
        emailOutbox.setStatus("VERIFIED");
        emailOutboxRepository.save(emailOutbox);
        
        return true;
    }

    /**
     * Send delivery status update email and write to outbox
     */
    public void sendDeliveryStatusEmail(String to, String orderId, Long deliveryId, String status) {
        try {
            EmailOutbox outbox = new EmailOutbox();
            outbox.setEmail(to);
            outbox.setOrderId(orderId);
            outbox.setTemplate("delivery_status_update");
            outbox.setStatus("PENDING");
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("orderId", orderId);
            payload.put("deliveryId", deliveryId);
            payload.put("status", status);
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            emailOutboxRepository.save(outbox);

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject("Delivery Status Update Notification");
            message.setText(String.format("Your order %s delivery status has been updated to: %s (Delivery ID: %s)", orderId, status, String.valueOf(deliveryId)));
            mailSender.send(message);

            outbox.setStatus("SENT");
            emailOutboxRepository.save(outbox);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send delivery notification: " + e.getMessage());
        }
    }

    public void sendOrderCancelledEmail(String to, String orderId, String reason) {
        try {
            EmailOutbox outbox = new EmailOutbox();
            outbox.setEmail(to);
            outbox.setOrderId(orderId);
            outbox.setTemplate("order_cancelled");
            outbox.setStatus("PENDING");
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("orderId", orderId);
            payload.put("reason", reason);
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            emailOutboxRepository.save(outbox);

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject("Order Cancelled");
            message.setText(String.format("Your order %s has been cancelled. Reason: %s", orderId, reason == null ? "None" : reason));
            mailSender.send(message);

            outbox.setStatus("SENT");
            emailOutboxRepository.save(outbox);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send cancellation notification: " + e.getMessage());
        }
    }

    public void sendOrderFailedEmail(String to, String orderId, String reason) {
        try {
            EmailOutbox outbox = new EmailOutbox();
            outbox.setEmail(to);
            outbox.setOrderId(orderId);
            outbox.setTemplate("order_failed");
            outbox.setStatus("PENDING");
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("orderId", orderId);
            payload.put("reason", reason);
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            emailOutboxRepository.save(outbox);

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject("Order Processing Failed");
            message.setText(String.format("Sorry, your order %s processing failed. Reason: %s. The system has cancelled this order.", orderId, reason == null ? "Unknown" : reason));
            mailSender.send(message);

            outbox.setStatus("SENT");
            emailOutboxRepository.save(outbox);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send failure notification: " + e.getMessage());
        }
    }

    public void sendRefundSuccessEmail(String to, String orderId, String refundTxnId) {
        try {
            EmailOutbox outbox = new EmailOutbox();
            outbox.setEmail(to);
            outbox.setOrderId(orderId);
            outbox.setTemplate("refund_success");
            outbox.setStatus("PENDING");
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("orderId", orderId);
            payload.put("refundTxnId", refundTxnId);
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            emailOutboxRepository.save(outbox);

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject("Refund Success Notification");
            message.setText(String.format("Your order %s refund was successful, refund transaction ID: %s.", orderId, refundTxnId));
            mailSender.send(message);

            outbox.setStatus("SENT");
            emailOutboxRepository.save(outbox);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send refund success notification: " + e.getMessage());
        }
    }
}