package comp5348.storeservice.config;

import comp5348.storeservice.model.Product;
import comp5348.storeservice.model.Account;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Component
public class StoreDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(StoreDataInitializer.class);

    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private AccountRepository accountRepository;

    @Override
    public void run(String... args) throws Exception {
        logger.info("Initializing default products (iPhone13~iPhone17) if absent...");

        List<SeedItem> seeds = Arrays.asList(
                new SeedItem("iPhone 13", new BigDecimal("6999.00"), "A15 仿生芯片，双摄系统", 20),
                new SeedItem("iPhone 14", new BigDecimal("7999.00"), "更强续航，车祸检测", 20),
                new SeedItem("iPhone 15", new BigDecimal("8999.00"), "A16，USB‑C，灵动岛", 20),
                new SeedItem("iPhone 16", new BigDecimal("9999.00"), "更强 AI 体验，拍照升级", 20),
                new SeedItem("iPhone 17", new BigDecimal("10999.00"), "新一代芯片与影像系统（示例数据）", 20)
        );

        for (SeedItem s : seeds) {
            boolean exists = productRepository.findByNameContainingIgnoreCase(s.name).stream()
                    .anyMatch(p -> p.getName().equalsIgnoreCase(s.name));
            if (!exists) {
                Product p = new Product();
                p.setName(s.name);
                p.setPrice(s.price);
                p.setDescription(s.desc);
                p.setStockQuantity(s.stock);
                productRepository.save(p);
                logger.info("Created product: {}", s.name);
            } else {
                logger.info("Product exists, skipped: {}", s.name);
            }
        }

        // 初始化默认用户: username: customer, password: COMP5348
        final String defaultEmail = "customer@example.com";
        if (!accountRepository.findByEmail(defaultEmail).isPresent()) {
            Account a = new Account();
            a.setFirstName("John");
            a.setLastName("Customer");
            a.setUsername("customer");
            a.setEmail(defaultEmail);
            a.setPassword(new BCryptPasswordEncoder().encode("COMP5348"));
            a.setEmailVerified(true);
            a.setActive(true);
            accountRepository.save(a);
            logger.info("Created default demo user: email={}, password=COMP5348", defaultEmail);
        } else {
            logger.info("Default demo user exists: {}", defaultEmail);
        }
    }

    private static class SeedItem {
        final String name; final BigDecimal price; final String desc; final int stock;
        SeedItem(String name, BigDecimal price, String desc, int stock) {
            this.name = name; this.price = price; this.desc = desc; this.stock = stock;
        }
    }
}
