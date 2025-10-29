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

    /**
     * 创建账户（需要邮箱验证）
     */
    @Transactional
    public AccountDTO createAccount(String firstName, String lastName, String email, String password) {
        if (accountRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists!");
        }
        
        // 创建 Account 实体对象
        Account account = new Account();
        account.setFirstName(firstName);
        account.setLastName(lastName);
        account.setEmail(email);
        String encodedPassword = passwordEncoder.encode(password);
        account.setPassword(encodedPassword);
        account.setEmailVerified(false); // 初始状态为未验证
        account.setActive(false); // 初始状态为未激活

        // 保存到数据库
        Account savedAccount = accountRepository.save(account);

        return new AccountDTO(
                savedAccount.getFirstName(),
                savedAccount.getLastName(),
                savedAccount.getEmail(),
                "********" // 不返回真实密码
        );
    }

    /**
     * 激活账户（邮箱验证成功后调用）
     */
    @Transactional
    public boolean activateAccount(String email) {
        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        if (accountOpt.isPresent()) {
            Account account = accountOpt.get();
            account.setEmailVerified(true);
            account.setActive(true);
            accountRepository.save(account);
            return true;
        }
        return false;
    }

    /**
     * 验证登录（只允许已验证的账户登录）
     */
    public boolean verifyLogin(String email, String password) {
        Optional<Account> userOpt = accountRepository.findByEmail(email);
        if (userOpt.isPresent()) {
            Account user = userOpt.get();
            // 检查账户是否激活且邮箱已验证
            if (!Boolean.TRUE.equals(user.getActive()) || !Boolean.TRUE.equals(user.getEmailVerified())) {
                return false;
            }
            return passwordEncoder.matches(password, user.getPassword());
        }
        return false;
    }

    /**
     * 根据邮箱获取账户信息
     */
    public Optional<Account> getAccountByEmail(String email) {
        return accountRepository.findByEmail(email);
    }

    /**
     * 检查邮箱是否存在
     */
    public boolean emailExists(String email) {
        return accountRepository.findByEmail(email).isPresent();
    }

    /**
     * 检查邮箱是否已验证
     */
    public boolean isEmailVerified(String email) {
        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getEmailVerified());
    }

    /**
     * 检查账户是否激活
     */
    public boolean isAccountActive(String email) {
        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getActive());
    }

    /**
     * 发送密码重置邮件
     */
    public boolean sendPasswordResetEmail(String email) {
        // 检查邮箱是否存在
        if (!emailExists(email)) {
            return false;
        }

        try {
            // 调用邮件服务发送密码重置验证码
            String url = EMAIL_SERVICE_URL + "/send-password-reset";
            PasswordResetRequest request = new PasswordResetRequest(email);
            
            PasswordResetResponse response = restTemplate.postForObject(url, request, PasswordResetResponse.class);
            return response != null && response.success;
        } catch (Exception e) {
            System.err.println("发送密码重置邮件失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 重置密码
     */
    @Transactional
    public boolean resetPassword(String email, String code, String newPassword) {
        // 检查邮箱是否存在
        if (!emailExists(email)) {
            return false;
        }

        try {
            // 验证重置码
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
