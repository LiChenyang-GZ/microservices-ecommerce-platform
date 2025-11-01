package comp5348.storeservice.service;


import comp5348.storeservice.model.Account;
import comp5348.storeservice.repository.AccountRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import comp5348.storeservice.dto.AccountDTO;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;
import java.beans.Transient;
import java.math.BigDecimal;
import java.util.Optional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final RestTemplate restTemplate = new RestTemplate();
    private final String EMAIL_SERVICE_URL = "http://localhost:8083/api/email";
    
    @Autowired
    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Autowired
    private comp5348.storeservice.adapter.BankAdapter bankAdapter;

    /**
     * Create account (requires email verification)
     */
    @Transactional
    public AccountDTO createAccount(String firstName, String lastName, String email, String password) {
        if (accountRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists!");
        }
        
        // Create Account entity object
        Account account = new Account();
        account.setFirstName(firstName);
        account.setLastName(lastName);
        account.setEmail(email);
        // Username must be unique: prioritize firstName.lastName combination; if missing, use email prefix; if still missing, use user
        String base = null;
        String fn = firstName != null ? firstName.trim() : "";
        String ln = lastName != null ? lastName.trim() : "";
        if (!fn.isEmpty() || !ln.isEmpty()) {
            base = (fn + (ln.isEmpty()? "" : "." + ln))
                    .replaceAll("\\s+", "")
                    .toLowerCase();
        }
        if (base == null || base.isEmpty()) {
            try { base = email != null ? email.split("@")[0] : null; } catch (Exception ignore) {}
        }
        if (base == null || base.isEmpty()) base = "user";
        String candidate = base;
        int suffix = 1;
        while (accountRepository.findByUsername(candidate).isPresent()) {
            candidate = base + suffix;
            suffix++;
        }
        account.setUsername(candidate);
        String encodedPassword = passwordEncoder.encode(password);
        account.setPassword(encodedPassword);
        account.setEmailVerified(false); // Initial state is unverified
        account.setActive(false); // Initial state is inactive

        // Save to database
        Account savedAccount = accountRepository.save(account);
        // Open account immediately after registration (default balance 10000) and bind to account
        try {
            String acctNo = bankAdapter.createCustomerAccount(email, new BigDecimal("10000"));
            if (acctNo != null) {
                savedAccount.setBankAccountNumber(acctNo);
                savedAccount = accountRepository.save(savedAccount);
            }
        } catch (Exception ignore) {}

        return new AccountDTO(
                savedAccount.getFirstName(),
                savedAccount.getLastName(),
                savedAccount.getEmail(),
                "********" // Do not return real password
        );
    }

    /**
     * Activate account (called after email verification succeeds)
     */
    @Transactional
    public boolean activateAccount(String email) {
        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        if (accountOpt.isPresent()) {
            Account account = accountOpt.get();
            account.setEmailVerified(true);
            account.setActive(true);
            // Open account for user upon activation (default balance 10000), only if bank account is not yet bound
            if (account.getBankAccountNumber() == null || account.getBankAccountNumber().isEmpty()) {
                try {
                    String acctNo = bankAdapter.createCustomerAccount(account.getEmail(), new java.math.BigDecimal("10000"));
                    if (acctNo != null) account.setBankAccountNumber(acctNo);
                } catch (Exception ignore) {}
            }
            accountRepository.save(account);
            return true;
        }
        return false;
    }

    /**
     * Verify login (only allow verified accounts to login)
     */
    public boolean verifyLogin(String identifier, String password) {
        Optional<Account> userOpt = accountRepository.findByEmail(identifier);
        if (!userOpt.isPresent()) {
            userOpt = accountRepository.findByUsername(identifier);
        }
        if (userOpt.isPresent()) {
            Account user = userOpt.get();
            // Check if account is active and email is verified
            if (!Boolean.TRUE.equals(user.getActive()) || !Boolean.TRUE.equals(user.getEmailVerified())) {
                return false;
            }
            return passwordEncoder.matches(password, user.getPassword());
        }
        return false;
    }

    /**
     * Get account information by email
     */
    public Optional<Account> getAccountByEmail(String email) {
        return accountRepository.findByEmail(email);
    }

    public Optional<Account> getAccountByIdentifier(String identifier) {
        Optional<Account> opt = accountRepository.findByEmail(identifier);
        if (!opt.isPresent()) opt = accountRepository.findByUsername(identifier);
        return opt;
    }

    /**
     * Check if email exists
     */
    public boolean emailExists(String email) {
        return accountRepository.findByEmail(email).isPresent();
    }

    /**
     * Check if email is verified
     */
    public boolean isEmailVerified(String identifier) {
        Optional<Account> accountOpt = accountRepository.findByEmail(identifier);
        if (!accountOpt.isPresent()) accountOpt = accountRepository.findByUsername(identifier);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getEmailVerified());
    }

    /**
     * Check if account is active
     */
    public boolean isAccountActive(String identifier) {
        Optional<Account> accountOpt = accountRepository.findByEmail(identifier);
        if (!accountOpt.isPresent()) accountOpt = accountRepository.findByUsername(identifier);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getActive());
    }

    /**
     * Send password reset email
     */
    public boolean sendPasswordResetEmail(String email) {
        // Check if email exists
        if (!emailExists(email)) {
            return false;
        }

        try {
            // Call email service to send password reset verification code
            String url = EMAIL_SERVICE_URL + "/send-password-reset";
            PasswordResetRequest request = new PasswordResetRequest(email);
            
            PasswordResetResponse response = restTemplate.postForObject(url, request, PasswordResetResponse.class);
            return response != null && response.success;
        } catch (Exception e) {
            System.err.println("Failed to send password reset email: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reset password
     */
    @Transactional
    public boolean resetPassword(String email, String code, String newPassword) {
        // Check if email exists
        if (!emailExists(email)) {
            return false;
        }

        try {
            // Verify reset code
            String url = EMAIL_SERVICE_URL + "/verify-password-reset";
            PasswordResetVerifyRequest request = new PasswordResetVerifyRequest(email, code);
            
            PasswordResetVerifyResponse response = restTemplate.postForObject(url, request, PasswordResetVerifyResponse.class);
            if (response == null || !response.success) {
                return false;
            }

            // 更新密码
            Optional<Account> accountOpt = accountRepository.findByEmail(email);
            if (accountOpt.isPresent()) {
                Account account = accountOpt.get();
                String encodedPassword = passwordEncoder.encode(newPassword);
                account.setPassword(encodedPassword);
                accountRepository.save(account);
                return true;
            }
            return false;
        } catch (Exception e) {
            System.err.println("重置密码失败: " + e.getMessage());
            return false;
        }
    }

    // 内部类用于API调用
    private static class PasswordResetRequest {
        public String email;
        
        public PasswordResetRequest(String email) {
            this.email = email;
        }
    }

    private static class PasswordResetResponse {
        public boolean success;
        public String message;
    }

    private static class PasswordResetVerifyRequest {
        public String email;
        public String code;
        
        public PasswordResetVerifyRequest(String email, String code) {
            this.email = email;
            this.code = code;
        }
    }

    private static class PasswordResetVerifyResponse {
        public boolean success;
        public String message;
    }
}
