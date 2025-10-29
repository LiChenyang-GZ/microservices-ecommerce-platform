package comp5348.storeservice.controller;

import comp5348.storeservice.dto.AccountDTO;
import comp5348.storeservice.service.AccountService;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "http://localhost:3000")
public class AccountController {

    private final AccountService accountService;

    @Autowired
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 创建账户（需要邮箱验证）
     */
    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody CreateAccountRequest request) {
        try {
            AccountDTO accountDTO = accountService.createAccount(
                    request.firstName, request.lastName, request.email, request.password
            );
            return ResponseEntity.ok(new CreateAccountResponse(true, "账户创建成功，请检查邮箱并完成验证", accountDTO));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new CreateAccountResponse(false, e.getMessage(), null));
        }
    }

    /**
     * 激活账户（邮箱验证成功后调用）
     */
    @PostMapping("/activate")
    public ResponseEntity<?> activateAccount(@RequestBody ActivateAccountRequest request) {
        boolean success = accountService.activateAccount(request.email);
        if (success) {
            return ResponseEntity.ok(new ActivateAccountResponse(true, "账户激活成功"));
        } else {
            return ResponseEntity.badRequest().body(new ActivateAccountResponse(false, "账户激活失败"));
        }
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody LoginRequest request) {
        boolean success = accountService.verifyLogin(request.email, request.password);
        if (success) {
            return ResponseEntity.ok(new LoginResponse(true, "登录成功"));
        } else {
            // 检查是否是邮箱未验证的问题
            if (accountService.emailExists(request.email) && !accountService.isEmailVerified(request.email)) {
                return ResponseEntity.status(401).body(new LoginResponse(false, "请先验证您的邮箱"));
            }
            return ResponseEntity.status(401).body(new LoginResponse(false, "邮箱或密码错误"));
        }
    }

    /**
     * 检查邮箱是否存在
     */
    @PostMapping("/check-email")
    public ResponseEntity<EmailCheckResponse> checkEmailExists(@RequestBody EmailCheckRequest request) {
        boolean exists = accountService.emailExists(request.email);
        return ResponseEntity.ok(new EmailCheckResponse(exists));
    }

    /**
     * 检查邮箱是否已验证
     */
    @GetMapping("/check-verified/{email}")
    public ResponseEntity<?> checkEmailVerified(@PathVariable String email) {
        boolean verified = accountService.isEmailVerified(email);
        return ResponseEntity.ok(new EmailVerifiedResponse(verified));
    }

    /**
     * 发送忘记密码邮件
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        try {
            boolean success = accountService.sendPasswordResetEmail(request.email);
            if (success) {
                return ResponseEntity.ok(new ForgotPasswordResponse(true, "密码重置邮件已发送，请检查您的邮箱"));
            } else {
                return ResponseEntity.badRequest().body(new ForgotPasswordResponse(false, "邮箱不存在或发送失败"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ForgotPasswordResponse(false, "发送失败：" + e.getMessage()));
        }
    }

    /**
     * 重置密码
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            boolean success = accountService.resetPassword(request.email, request.code, request.newPassword);
            if (success) {
                return ResponseEntity.ok(new ResetPasswordResponse(true, "密码重置成功"));
            } else {
                return ResponseEntity.badRequest().body(new ResetPasswordResponse(false, "验证码无效或已过期"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ResetPasswordResponse(false, "重置失败：" + e.getMessage()));
        }
    }

    // 请求和响应类
    public static class CreateAccountRequest {
        public String firstName;
        public String lastName;
        public String email;
        public String password;
    }

    public static class CreateAccountResponse {
        public boolean success;
        public String message;
        public AccountDTO account;

        public CreateAccountResponse(boolean success, String message, AccountDTO account) {
            this.success = success;
            this.message = message;
            this.account = account;
        }
    }

    public static class ActivateAccountRequest {
        public String email;
    }

    public static class ActivateAccountResponse {
        public boolean success;
        public String message;

        public ActivateAccountResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class LoginRequest {
        public String email;
        public String password;
    }

    public static class LoginResponse {
        public boolean success;
        public String message;

        public LoginResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class EmailCheckRequest {
        public String email;
    }

    public static class EmailCheckResponse {
        public boolean exists;
        
        public EmailCheckResponse(boolean exists) {
            this.exists = exists;
        }
    }

    public static class EmailVerifiedResponse {
        public boolean verified;

        public EmailVerifiedResponse(boolean verified) {
            this.verified = verified;
        }
    }

    public static class ForgotPasswordRequest {
        public String email;
    }

    public static class ForgotPasswordResponse {
        public boolean success;
        public String message;

        public ForgotPasswordResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class ResetPasswordRequest {
        public String email;
        public String code;
        public String newPassword;
    }

    public static class ResetPasswordResponse {
        public boolean success;
        public String message;

        public ResetPasswordResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
