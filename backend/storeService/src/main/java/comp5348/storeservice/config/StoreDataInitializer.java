package comp5348.storeservice.config;

import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.WarehouseRepository;
import comp5348.storeservice.repository.WarehouseProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StoreDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(StoreDataInitializer.class);

    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private WarehouseProductRepository warehouseProductRepository;

    @Override
    @Transactional
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
                // 不再使用产品表库存，置 0 以避免误导
                p.setStockQuantity(0);
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

        // 初始化三个仓库，每个仓库都有 iPhone13-17，但数量不同
        logger.info("Initializing warehouses with products...");
        initializeWarehouses();
    }

    private void initializeWarehouses() {
        // 定义三个仓库
        List<WarehouseInfo> warehouses = Arrays.asList(
            new WarehouseInfo("主仓库", "北京市朝阳区仓库1号", new int[]{50, 40, 30, 20, 10}),  // iPhone 13-17 的数量
            new WarehouseInfo("上海仓库", "上海市浦东新区仓库2号", new int[]{30, 25, 20, 15, 10}),
            new WarehouseInfo("广州仓库", "广州市天河区仓库3号", new int[]{20, 15, 10, 10, 5})
        );

        // 获取所有iPhone商品（按名称顺序）
        List<String> iphoneNames = Arrays.asList("iPhone 13", "iPhone 14", "iPhone 15", "iPhone 16", "iPhone 17");
        List<Long> productIds = iphoneNames.stream()
                .map(name -> productRepository.findByNameContainingIgnoreCase(name).stream()
                        .filter(p -> p.getName().equalsIgnoreCase(name))
                        .map(Product::getId)
                        .findFirst())
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (productIds.size() != 5) {
            logger.warn("Not all iPhone products found, expected 5 but found {}", productIds.size());
            return;
        }

        // 创建仓库并分配商品
        for (int i = 0; i < warehouses.size(); i++) {
            WarehouseInfo whInfo = warehouses.get(i);
            
            // 检查仓库是否已存在
            Optional<Warehouse> existingWarehouse = warehouseRepository.findAll().stream()
                    .filter(w -> w.getName().equals(whInfo.name))
                    .findFirst();

            Warehouse warehouse;
            if (existingWarehouse.isPresent()) {
                warehouse = existingWarehouse.get();
                logger.info("Warehouse already exists: {}", whInfo.name);
            } else {
                // 创建新仓库
                warehouse = new Warehouse();
                warehouse.setName(whInfo.name);
                warehouse.setLocation(whInfo.location);
                warehouse.setModifyTime(LocalDateTime.now());
                warehouse = warehouseRepository.save(warehouse);
                logger.info("Created warehouse: {} at {}", whInfo.name, whInfo.location);
            }

            // 为每个商品分配到该仓库
            for (int j = 0; j < productIds.size() && j < whInfo.quantities.length; j++) {
                // 使用已初始化实体，避免懒加载跨会话
                final Long pid = productIds.get(j);
                Product product = productRepository.findById(pid)
                        .orElseThrow(() -> new IllegalStateException("Product not found by id: " + pid));
                int quantity = whInfo.quantities[j];

                // 检查该商品是否已在该仓库
                Optional<WarehouseProduct> existing = warehouseProductRepository
                        .findByWarehouseIdAndProductId(warehouse.getId(), product.getId());

                WarehouseProduct warehouseProduct;
                if (existing.isPresent()) {
                    // 更新数量
                    warehouseProduct = existing.get();
                    warehouseProduct.setQuantity(quantity);
                    warehouseProduct.setModifyTime(LocalDateTime.now());
                    logger.info("Updated {} in {}: quantity = {}", product.getName(), whInfo.name, quantity);
                } else {
                    // 创建新的 WarehouseProduct
                    warehouseProduct = new WarehouseProduct();
                    warehouseProduct.setWarehouse(warehouse);
                    warehouseProduct.setProduct(product);
                    warehouseProduct.setQuantity(quantity);
                    warehouseProduct.setModifyTime(LocalDateTime.now());
                    logger.info("Assigned {} to {}: quantity = {}", product.getName(), whInfo.name, quantity);
                }
                warehouseProductRepository.save(warehouseProduct);
            }
        }

        logger.info("Warehouse initialization completed!");
    }

    private static class SeedItem {
        final String name; final BigDecimal price; final String desc; final int stock;
        SeedItem(String name, BigDecimal price, String desc, int stock) {
            this.name = name; this.price = price; this.desc = desc; this.stock = stock;
        }
    }

    private static class WarehouseInfo {
        final String name;
        final String location;
        final int[] quantities; // iPhone 13, 14, 15, 16, 17 的数量

        WarehouseInfo(String name, String location, int[] quantities) {
            this.name = name;
            this.location = location;
            this.quantities = quantities;
        }
    }
}
