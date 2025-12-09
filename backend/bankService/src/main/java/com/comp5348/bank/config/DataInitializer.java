package com.comp5348.bank.config;

import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.repository.BankAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
public class DataInitializer implements CommandLineRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);
    
    @Autowired
    private BankAccountRepository accountRepository;
    
    @Override
    public void run(String... args) {
        logger.info("Initializing Bank accounts...");

        // SYSTEM Account (for deposits and external transfers)
        if (!accountRepository.existsByAccountNumber("SYSTEM")) {
            BankAccount systemAccount = new BankAccount();
            systemAccount.setAccountNumber("SYSTEM");
            systemAccount.setBalance(new BigDecimal("999999999.00")); // Very high balance
            systemAccount.setOwnerName("SYSTEM");
            systemAccount.setCreatedAt(LocalDateTime.now());
            accountRepository.save(systemAccount);
            logger.info("Created SYSTEM Account with balance $999,999,999");
        }

        // Store Account
        if (!accountRepository.existsByAccountNumber("STORE_ACCOUNT_001")) {
            BankAccount storeAccount = new BankAccount();
            storeAccount.setAccountNumber("STORE_ACCOUNT_001");
            storeAccount.setBalance(BigDecimal.ZERO);
            storeAccount.setOwnerName("Online Store");
            storeAccount.setCreatedAt(LocalDateTime.now());
            accountRepository.save(storeAccount);
            logger.info("Created Store Account: STORE_ACCOUNT_001");
        }
        
        // Customer Account
        if (!accountRepository.existsByAccountNumber("CUSTOMER_ACCOUNT_001")) {
            BankAccount customerAccount = new BankAccount();
            customerAccount.setAccountNumber("CUSTOMER_ACCOUNT_001");
            customerAccount.setBalance(new BigDecimal("10000.00"));
            customerAccount.setOwnerName("Test Customer");
            customerAccount.setCreatedAt(LocalDateTime.now());
            accountRepository.save(customerAccount);
            logger.info("Created Customer Account: CUSTOMER_ACCOUNT_001 with balance $10,000");
        }
        
        // Additional test accounts
        if (!accountRepository.existsByAccountNumber("CUSTOMER_ACCOUNT_002")) {
            BankAccount customer2Account = new BankAccount();
            customer2Account.setAccountNumber("CUSTOMER_ACCOUNT_002");
            customer2Account.setBalance(new BigDecimal("5000.00"));
            customer2Account.setOwnerName("Customer Two");
            customer2Account.setCreatedAt(LocalDateTime.now());
            accountRepository.save(customer2Account);
            logger.info("Created Customer Account: CUSTOMER_ACCOUNT_002 with balance $5,000");
        }
        
        if (!accountRepository.existsByAccountNumber("CUSTOMER_ACCOUNT_003")) {
            BankAccount customer3Account = new BankAccount();
            customer3Account.setAccountNumber("CUSTOMER_ACCOUNT_003");
            customer3Account.setBalance(new BigDecimal("100.00"));
            customer3Account.setOwnerName("Customer Low Balance");
            customer3Account.setCreatedAt(LocalDateTime.now());
            accountRepository.save(customer3Account);
            logger.info("Created Customer Account: CUSTOMER_ACCOUNT_003 with balance $100");
        }
        
        logger.info("Bank account initialization completed");
    }
}


