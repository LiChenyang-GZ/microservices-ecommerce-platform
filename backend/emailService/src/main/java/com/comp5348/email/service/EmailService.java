package com.comp5348.email.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import com.comp5348.email.model.EmailOutbox;
import com.comp5348.email.repository.EmailOutboxRepository;
import java.security.SecureRandom;
import java.util.List;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;
    
    @Autowired
    private EmailOutboxRepository emailOutboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    static final String REGISTRATION = "REGISTRATION";
    static final String PASSWORD_RESET = "PASSWORD_RESET";
    /** Wrong guesses a code survives; the next wrong one after this many kills it (A11, V13) */
    static final int MAX_FAILED_ATTEMPTS = 5;

    /** Codes must not be predictable from earlier ones: java.util.Random is a linear generator, SecureRandom is not */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generate 6-digit verification code
     */
    public String generateVerificationCode() {
        return String.format("%06d", RANDOM.nextInt(1000000));
    }

    /** Store a new code (PENDING). The older codes stay live until this one is sent: see markSent */
    private EmailOutbox newCode(String email, String verificationType) {
        return emailOutboxRepository.save(new EmailOutbox(email, generateVerificationCode(), verificationType));
    }

    /**
     * The mail went out: this code is live, the address's older codes of the same kind are not (A11, V14).
     * Only now, not before sending: if sending fails, the code the user already has keeps working (review 51 🟡2).
     */
    private void markSent(EmailOutbox row) {
        row.setStatus("SENT");
        emailOutboxRepository.save(row);
        emailOutboxRepository.expireOtherLiveCodes(row.getEmail(), row.getVerificationType(), row.getId());
    }

    private void markFailed(EmailOutbox row) {
        row.setStatus("FAILED");
        row.setRetryCount(row.getRetryCount() + 1);
        emailOutboxRepository.save(row);
    }

    /**
     * Send registration verification code
     */
    public String sendRegistrationVerificationCode(String email) {
        EmailOutbox row = newCode(email, REGISTRATION);
        sendVerificationCode(row, "Registration Verification Code");
        return row.getCode();
    }

    /**
     * Send verification code email
     */
    private void sendVerificationCode(EmailOutbox row, String type) {
        String code = row.getCode();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(row.getEmail());
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
        } catch (Exception e) {
            markFailed(row);
            throw new RuntimeException("Email sending failed: " + e.getMessage());
        }
        markSent(row);
    }

    /**
     * Check a registration code. Called only by store's activate (service token, A11), never by the browser.
     * Only REGISTRATION codes count: a password-reset code does not activate an account (V11).
     */
    @Transactional
    public boolean verifyCode(String email, String code) {
        return checkCode(email, code, REGISTRATION);
    }

    /**
     * The one place a code is checked. The caller's transaction holds the row locks until it commits.
     *   right code, not expired → VERIFIED (used up), true
     *   right code, expired     → EXPIRED, false (V15)
     *   wrong code              → every live code of this kind for the address counts one more wrong guess;
     *                             at MAX_FAILED_ATTEMPTS it is EXPIRED and even the right code fails (V13)
     */
    private boolean checkCode(String email, String code, String verificationType) {
        List<EmailOutbox> live = emailOutboxRepository.lockCodes(email, verificationType, List.of("SENT"));
        for (EmailOutbox row : live) {
            if (row.getCode().equals(code)) {
                if (row.isExpired()) {
                    row.setStatus("EXPIRED");
                    return false;
                }
                row.setStatus("VERIFIED");
                return true;
            }
        }
        for (EmailOutbox row : live) {
            row.setFailedAttempts(row.getFailedAttempts() + 1);
            if (row.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                row.setStatus("EXPIRED");
            }
        }
        return false;
    }

    /**
     * Send password reset verification code
     */
    public String sendPasswordResetCode(String email) {
        EmailOutbox row = newCode(email, PASSWORD_RESET);
        sendPasswordResetEmail(row);
        return row.getCode();
    }

    /**
     * Send password reset email
     */
    private void sendPasswordResetEmail(EmailOutbox row) {
        String code = row.getCode();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(row.getEmail());
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
        } catch (Exception e) {
            markFailed(row);
            throw new RuntimeException("Password reset email sending failed: " + e.getMessage());
        }
        markSent(row);
    }

    /**
     * Check a password-reset code (store's reset-password, service token). Only PASSWORD_RESET codes count (V12).
     */
    @Transactional
    public boolean verifyPasswordResetCode(String email, String code) {
        return checkCode(email, code, PASSWORD_RESET);
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