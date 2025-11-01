package comp5348.storeservice.controller;

import comp5348.storeservice.dto.AccountDTO;
import comp5348.storeservice.dto.TokenValidationRequest;
import comp5348.storeservice.dto.TokenValidationResponse;
import comp5348.storeservice.service.AccountService;
import comp5348.storeservice.service.TokenValidationService;
import comp5348.storeservice.utils.JwtUtil;
import comp5348.storeservice.model.Account;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "http://localhost:3000")
public class AccountController {

    private final AccountService accountService;
    private final JwtUtil jwtUtil;
    private final TokenValidationService tokenValidationService;

    @Autowired
    public AccountController(AccountService accountService, JwtUtil jwtUtil, TokenValidationService tokenValidationService) {
        this.accountService = accountService;
        this.jwtUtil = jwtUtil;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Create account (requires email verification)
     */
    @PostMapping
    public ResponseEntity<?> createAccount(@RequestBody CreateAccountRequest request) {
        try {
            AccountDTO accountDTO = accountService.createAccount(
                    request.username, request.email, request.password
            );
            return ResponseEntity.ok(new CreateAccountResponse(true, "Account created successfully, please check your email and complete verification", accountDTO));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new CreateAccountResponse(false, e.getMessage(), null));
        }
    }

    /**
     * Activate account (called after email verification succeeds)
     */
    @PostMapping("/activate")
    public ResponseEntity<?> activateAccount(@RequestBody ActivateAccountRequest request) {
        boolean success = accountService.activateAccount(request.email);
        if (success) {
            return ResponseEntity.ok(new ActivateAccountResponse(true, "Account activated successfully"));
        } else {
            return ResponseEntity.badRequest().body(new ActivateAccountResponse(false, "Account activation failed"));
        }
    }

    /**
     * User login
     */
    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody LoginRequest request) {
        boolean success = accountService.verifyLogin(request.email, request.password);
        if (success) {
            // Get user information and generate JWT token
            Optional<Account> accountOpt = accountService.getAccountByIdentifier(request.email);
            if (accountOpt.isPresent()) {
                Account account = accountOpt.get();
                String token = jwtUtil.generateToken(account.getId(), account.getEmail());
                return ResponseEntity.ok(new LoginResponse(true, "Login successful", token, account.getId(), account.getEmail()));
            }
            return ResponseEntity.ok(new LoginResponse(true, "Login successful", null, null, null));
        } else {
            // Check if it's an unverified email issue
            if (accountService.emailExists(request.email) && !accountService.isEmailVerified(request.email)) {
                return ResponseEntity.status(401).body(new LoginResponse(false, "Please verify your email first", null, null, null));
            }
            return ResponseEntity.status(401).body(new LoginResponse(false, "Email or password incorrect", null, null, null));
        }
    }

    /**
     * Check if email exists
     */
    @PostMapping("/check-email")
    public ResponseEntity<EmailCheckResponse> checkEmailExists(@RequestBody EmailCheckRequest request) {
        boolean exists = accountService.emailExists(request.email);
        return ResponseEntity.ok(new EmailCheckResponse(exists));
    }

    /**
     * Check if email is verified
     */
    @GetMapping("/check-verified/{email}")
    public ResponseEntity<?> checkEmailVerified(@PathVariable String email) {
        boolean verified = accountService.isEmailVerified(email);
        return ResponseEntity.ok(new EmailVerifiedResponse(verified));
    }

    /**
     * Send forgot password email
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        try {
            boolean success = accountService.sendPasswordResetEmail(request.email);
            if (success) {
                return ResponseEntity.ok(new ForgotPasswordResponse(true, "Password reset email sent, please check your email"));
            } else {
                return ResponseEntity.badRequest().body(new ForgotPasswordResponse(false, "Email does not exist or sending failed"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ForgotPasswordResponse(false, "Failed to send: " + e.getMessage()));
        }
    }

    /**
     * Reset password
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            boolean success = accountService.resetPassword(request.email, request.code, request.newPassword);
            if (success) {
                return ResponseEntity.ok(new ResetPasswordResponse(true, "Password reset successful"));
            } else {
                return ResponseEntity.badRequest().body(new ResetPasswordResponse(false, "Verification code invalid or expired"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ResetPasswordResponse(false, "Reset failed: " + e.getMessage()));
        }
    }

    /**
     * Validate token (for other services to call)
     */
    @PostMapping("/validate-token")
    public ResponseEntity<TokenValidationResponse> validateToken(@RequestBody TokenValidationRequest request) {
        TokenValidationResponse response = tokenValidationService.validateToken(request.getToken());
        if (response.isValid()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(401).body(response);
        }
    }

    // Request and response classes
    public static class CreateAccountRequest {
        public String username;
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
        public String token;
        public Long userId;
        public String email;

        public LoginResponse(boolean success, String message, String token, Long userId, String email) {
            this.success = success;
            this.message = message;
            this.token = token;
            this.userId = userId;
            this.email = email;
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
