package comp5348.storeservice.controller;

import comp5348.storeservice.dto.AccountDTO;
import comp5348.storeservice.dto.TokenValidationRequest;
import comp5348.storeservice.dto.TokenValidationResponse;
import comp5348.storeservice.ratelimit.AuthRateLimits;
import comp5348.storeservice.ratelimit.ClientIp;
import comp5348.storeservice.ratelimit.RateLimiter;
import comp5348.storeservice.service.AccountService;
import comp5348.storeservice.service.TokenValidationService;
import comp5348.storeservice.utils.JwtUtil;
import comp5348.storeservice.model.Account;
import jakarta.servlet.http.HttpServletRequest;
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
    /** Rate limits on the public endpoints below (Obsidian 54); over the limit → 429 + Retry-After */
    private final AuthRateLimits limits;

    @Autowired
    public AccountController(AccountService accountService, JwtUtil jwtUtil, TokenValidationService tokenValidationService,
                             AuthRateLimits limits) {
        this.accountService = accountService;
        this.jwtUtil = jwtUtil;
        this.tokenValidationService = tokenValidationService;
        this.limits = limits;
    }

    /** 429 with how long until this window ends, in the same {success, message} shape as the other answers */
    private static ResponseEntity<SimpleResponse> tooManyAttempts(RateLimiter.Decision decision) {
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(decision.retryAfterSeconds()))
                .body(new SimpleResponse(false,
                        "Too many attempts, please try again in " + decision.retryAfterSeconds() + " seconds"));
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
     * Activate an account with the code from the registration email (A11): store checks the code with email itself.
     * A wrong code and an unknown email get the same answer. That hides little: registration already says
     * "Email already exists", and an account that is already active gets 200 without spending a code (review 51 🟡7).
     */
    @PostMapping("/activate")
    public ResponseEntity<?> activateAccount(@RequestBody ActivateAccountRequest request, HttpServletRequest http) {
        Optional<RateLimiter.Decision> blocked = limits.activate(ClientIp.of(http));
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get()); // before email is asked, so a blocked guess does not burn the code
        }
        return switch (accountService.activateAccount(request.email, request.code)) {
            case ACTIVATED -> ResponseEntity.ok(new ActivateAccountResponse(true, "Account activated successfully"));
            case ALREADY_ACTIVE -> ResponseEntity.ok(new ActivateAccountResponse(true, "Account is already activated"));
            case INVALID_CODE -> ResponseEntity.badRequest()
                    .body(new ActivateAccountResponse(false, "Verification code is invalid or expired"));
            case EMAIL_UNAVAILABLE -> ResponseEntity.status(503)
                    .body(new ActivateAccountResponse(false, "Verification is unavailable right now, please try again later"));
        };
    }

    /**
     * Send a registration code (rate limits, Obsidian 54). The browser used to call email directly; now store
     * counts the request, looks at the account and asks email with its service token. No account, or an active
     * one: the same 200, and nothing is sent.
     */
    @PostMapping("/send-verification")
    public ResponseEntity<?> sendVerificationCode(@RequestBody SendVerificationRequest request, HttpServletRequest http) {
        Optional<RateLimiter.Decision> blocked = limits.sendVerification(ClientIp.of(http), request.email);
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get());
        }
        return switch (accountService.sendVerificationCode(request.email)) {
            case SENT, NOTHING_TO_SEND ->
                    ResponseEntity.ok(new SimpleResponse(true, "Verification code has been sent to your email"));
            case EMAIL_UNAVAILABLE -> ResponseEntity.status(503)
                    .body(new SimpleResponse(false, "Sending is unavailable right now, please try again later"));
        };
    }

    /**
     * User login. Counted before the password is checked: once over the limit even the right password gets 429,
     * otherwise the answer would tell a guesser they got it. A successful login clears its IP + account count.
     *
     * The count is per account, not per text typed: the box takes an email or a username, and both reach the same
     * account (AccountService#verifyLogin). Keyed by what was typed, "alice@x.com" and "alice" were two counts, so 20
     * guesses per 15 minutes instead of 10 (review 2, Obsidian 56). An identifier that matches no account is counted
     * by its text; the two prefixes keep a typed "account:7" apart from account 7.
     */
    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = ClientIp.of(http);
        Optional<Account> accountOpt = accountService.getAccountByIdentifier(request.email);
        String counted = accountOpt.map(a -> "account:" + a.getId()).orElse("typed:" + request.email);
        Optional<RateLimiter.Decision> blocked = limits.login(ip, counted);
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get());
        }
        boolean success = accountService.verifyLogin(request.email, request.password);
        if (success) {
            limits.loginSucceeded(ip, counted);
            // Generate the JWT for the account found above
            if (accountOpt.isPresent()) {
                Account account = accountOpt.get();
                String token = jwtUtil.generateToken(account.getId(), account.getEmail(), account.getRole());
                return ResponseEntity.ok(new LoginResponse(true, "Login successful", token, account.getId(),
                        account.getEmail(), account.getRole().name()));
            }
            return ResponseEntity.ok(new LoginResponse(true, "Login successful", null, null, null, null));
        } else {
            // Check if it's an unverified email issue
            if (accountService.emailExists(request.email) && !accountService.isEmailVerified(request.email)) {
                return ResponseEntity.status(401).body(new LoginResponse(false, "Please verify your email first", null, null, null, null));
            }
            return ResponseEntity.status(401).body(new LoginResponse(false, "Email or password incorrect", null, null, null, null));
        }
    }

    /**
     * Send forgot password email
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        Optional<RateLimiter.Decision> blocked = limits.forgotPassword(ClientIp.of(http), request.email);
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get());
        }
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
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        Optional<RateLimiter.Decision> blocked = limits.resetPassword(ClientIp.of(http));
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get()); // before email is asked, as in activate
        }
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

    public static class SendVerificationRequest {
        public String email;
    }

    /** {success, message}: the send-verification answers and every 429 */
    public static class SimpleResponse {
        public boolean success;
        public String message;

        public SimpleResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class ActivateAccountRequest {
        public String email;
        /** the 6-digit code from the registration email */
        public String code;
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
        /** USER or ADMIN: the frontend shows the admin dashboard only to ADMIN (the backend checks again) */
        public String role;

        public LoginResponse(boolean success, String message, String token, Long userId, String email, String role) {
            this.role = role;
            this.success = success;
            this.message = message;
            this.token = token;
            this.userId = userId;
            this.email = email;
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
