package comp5348.storeservice.config;

import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.ProductRepository;
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
import org.springframework.transaction.annotation.Transactional;

@Component
public class StoreDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(StoreDataInitializer.class);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private WarehouseProductRepository warehouseProductRepository;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        logger.info("Initializing default products (iPhone13~iPhone17) if absent...");

        List<SeedItem> seeds = Arrays.asList(
                new SeedItem("iPhone 13", new BigDecimal("6999.00"), "A15 Bionic chip, dual camera system", 20),
                new SeedItem("iPhone 14", new BigDecimal("7999.00"), "Longer battery life, crash detection", 20),
                new SeedItem("iPhone 15", new BigDecimal("8999.00"), "A16 chip, USB-C, Dynamic Island", 20),
                new SeedItem("iPhone 16", new BigDecimal("9999.00"), "Enhanced AI experience, camera upgrade", 20),
                new SeedItem("iPhone 17", new BigDecimal("10999.00"), "Next-generation chip and imaging system (sample data)", 20)
        );

        for (SeedItem s : seeds) {
            boolean exists = productRepository.findByNameContainingIgnoreCase(s.name).stream()
                    .anyMatch(p -> p.getName().equalsIgnoreCase(s.name));
            if (!exists) {
                Product p = new Product();
                p.setName(s.name);
                p.setPrice(s.price);
                p.setDescription(s.desc);
                // No longer using product table stock, set to 0 to avoid confusion
                p.setStockQuantity(0);
                productRepository.save(p);
                logger.info("Created product: {}", s.name);
            } else {
                logger.info("Product exists, skipped: {}", s.name);
            }
        }

        // Initialize three warehouses, each product has total inventory of 10, distributed across warehouses
        // Some warehouses have the product, some don't (won't overwrite existing data)
        logger.info("Initializing warehouses with products...");
        initializeWarehouses();
    }

    private void initializeWarehouses() {
        // Define three warehouses
        List<WarehouseInfo> warehouses = Arrays.asList(
            new WarehouseInfo("Main Warehouse", "Warehouse 1, Chaoyang District, Beijing"),
            new WarehouseInfo("Shanghai Warehouse", "Warehouse 2, Pudong New Area, Shanghai"),
            new WarehouseInfo("Guangzhou Warehouse", "Warehouse 3, Tianhe District, Guangzhou")
        );

        // Get all iPhone products (in name order)
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

        // Each product has total inventory of 10, distributed across different warehouses
        // Define inventory distribution for each product in different warehouses (index: Main, Shanghai, Guangzhou)
        // Some warehouses have the product, some don't (0 means the warehouse doesn't have the product)
        int[][] productDistribution = {
            {4, 6, 0},  // iPhone 13: Main 4, Shanghai 6, Guangzhou 0 (total 10)
            {3, 0, 7},  // iPhone 14: Main 3, Shanghai 0, Guangzhou 7 (total 10)
            {2, 3, 5},  // iPhone 15: Main 2, Shanghai 3, Guangzhou 5 (total 10)
            {10, 0, 0}, // iPhone 16: Main 10, Shanghai 0, Guangzhou 0 (total 10)
            {6, 4, 0}   // iPhone 17: Main 6, Shanghai 4, Guangzhou 0 (total 10)
        };

        // Ensure all products have total inventory of 10
        for (int i = 0; i < productDistribution.length; i++) {
            int total = Arrays.stream(productDistribution[i]).sum();
            if (total != 10) {
                logger.warn("Product {} total inventory is {}, expected 10. Adjusting...", iphoneNames.get(i), total);
            }
        }

        // Create warehouses and assign products
        for (int warehouseIndex = 0; warehouseIndex < warehouses.size(); warehouseIndex++) {
            WarehouseInfo whInfo = warehouses.get(warehouseIndex);
            
            // Check if warehouse already exists
            Optional<Warehouse> existingWarehouse = warehouseRepository.findAll().stream()
                    .filter(w -> w.getName().equals(whInfo.name))
                    .findFirst();

            Warehouse warehouse;
            if (existingWarehouse.isPresent()) {
                warehouse = existingWarehouse.get();
                logger.info("Warehouse already exists: {}", whInfo.name);
            } else {
                // Create new warehouse
                warehouse = new Warehouse();
                warehouse.setName(whInfo.name);
                warehouse.setLocation(whInfo.location);
                warehouse.setModifyTime(LocalDateTime.now());
                warehouse = warehouseRepository.save(warehouse);
                logger.info("Created warehouse: {} at {}", whInfo.name, whInfo.location);
            }

            // Assign each product to this warehouse (only products with quantity > 0)
            for (int productIndex = 0; productIndex < productIds.size(); productIndex++) {
                final Long pid = productIds.get(productIndex);
                Product product = productRepository.findById(pid)
                        .orElseThrow(() -> new IllegalStateException("Product not found by id: " + pid));
                
                // Get the inventory quantity of this product in this warehouse
                int quantity = productDistribution[productIndex][warehouseIndex];

                // If this warehouse doesn't have this product (quantity is 0), skip
                if (quantity == 0) {
                    continue;
                }

                // Check if this product already exists in this warehouse (if exists, skip, don't overwrite)
                Optional<WarehouseProduct> existing = warehouseProductRepository
                        .findByWarehouseIdAndProductId(warehouse.getId(), product.getId());

                if (existing.isPresent()) {
                    // If exists, skip (don't overwrite)
                    logger.info("WarehouseProduct already exists for {} in {}, skipped (not overwriting)", 
                            product.getName(), whInfo.name);
                } else {
                    // Create new WarehouseProduct
                    WarehouseProduct warehouseProduct = new WarehouseProduct();
                    warehouseProduct.setWarehouse(warehouse);
                    warehouseProduct.setProduct(product);
                    warehouseProduct.setQuantity(quantity);
                    warehouseProduct.setModifyTime(LocalDateTime.now());
                    warehouseProductRepository.save(warehouseProduct);
                    logger.info("Assigned {} to {}: quantity = {}", product.getName(), whInfo.name, quantity);
                }
            }
        }

        logger.info("Warehouse initialization completed! Each product has total inventory of 10, distributed across warehouses.");
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

        WarehouseInfo(String name, String location) {
            this.name = name;
            this.location = location;
        }
    }
}
