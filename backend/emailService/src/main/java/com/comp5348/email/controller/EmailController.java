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
     * 发送注册验证码
     */
    @PostMapping("/send-verification")
    public ResponseEntity<?> sendVerificationCode(@RequestBody SendVerificationRequest request) {
        try {
            String code = emailService.sendRegistrationVerificationCode(request.email);
            return ResponseEntity.ok(new SendVerificationResponse(true, "验证码已发送到您的邮箱"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SendVerificationResponse(false, "发送失败: " + e.getMessage()));
        }
    }

    /**
     * 验证验证码
     */
    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(@RequestBody VerifyCodeRequest request) {
        try {
            boolean isValid = emailService.verifyCode(request.email, request.code);
            if (isValid) {
                return ResponseEntity.ok(new VerifyCodeResponse(true, "验证成功"));
            } else {
                return ResponseEntity.badRequest().body(new VerifyCodeResponse(false, "验证码无效或已过期"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new VerifyCodeResponse(false, "验证失败: " + e.getMessage()));
        }
    }

    /**
     * 检查邮箱是否已验证
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
     * 发送密码重置验证码
     */
    @PostMapping("/send-password-reset")
    public ResponseEntity<?> sendPasswordResetCode(@RequestBody SendPasswordResetRequest request) {
        try {
            String code = emailService.sendPasswordResetCode(request.email);
            return ResponseEntity.ok(new SendPasswordResetResponse(true, "密码重置验证码已发送到您的邮箱"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new SendPasswordResetResponse(false, "发送失败: " + e.getMessage()));
        }
    }

    /**
     * 验证密码重置验证码
     */
    @PostMapping("/verify-password-reset")
    public ResponseEntity<?> verifyPasswordResetCode(@RequestBody VerifyPasswordResetRequest request) {
        try {
            boolean isValid = emailService.verifyPasswordResetCode(request.email, request.code);
            if (isValid) {
                return ResponseEntity.ok(new VerifyPasswordResetResponse(true, "验证成功"));
            } else {
                return ResponseEntity.badRequest().body(new VerifyPasswordResetResponse(false, "验证码无效或已过期"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new VerifyPasswordResetResponse(false, "验证失败: " + e.getMessage()));
        }
    }

    // 请求和响应类
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
}
