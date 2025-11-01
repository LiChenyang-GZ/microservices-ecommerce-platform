package comp5348.storeservice.service;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.repository.WarehouseProductRepository;
import comp5348.storeservice.repository.WarehouseRepository;
import comp5348.storeservice.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ProductService {
    
    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);
    
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private WarehouseProductRepository warehouseProductRepository;

    @Autowired
    private WarehouseService warehouseService;
    
    /**
     * Get all product list
     */
    public List<ProductDTO> getAllProducts() {
        logger.info("Fetching all products");
        return productRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Get products with stock list
     */
    public List<ProductDTO> getAvailableProducts() {
        logger.info("Fetching available products");
        // Filter based on warehouse aggregated inventory (replacing Product.stockQuantity)
        return productRepository.findAll().stream()
                .map(this::convertToDTO)
                .filter(dto -> dto.getStockQuantity() != null && dto.getStockQuantity() > 0)
                .collect(Collectors.toList());
    }
    
    /**
     * Get product by ID
     */
    public Optional<ProductDTO> getProductById(Long productId) {
        logger.info("Fetching product by id: {}", productId);
        return productRepository.findById(productId)
                .map(this::convertToDTO);
    }
    
    /**
     * Search products by name
     */
    public List<ProductDTO> searchProductsByName(String name) {
        logger.info("Searching products by name: {}", name);
        return productRepository.findByNameContainingIgnoreCase(name).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Search products by price range
     */
    public List<ProductDTO> searchProductsByPriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        logger.info("Searching products by price range: {} - {}", minPrice, maxPrice);
        return productRepository.findByPriceRange(minPrice, maxPrice).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * Create new product (does not handle inventory)
     */
    public ProductDTO createProduct(ProductDTO productDTO) {
        logger.info("Creating new product: {}", productDTO.getName());

        Product product = new Product();
        product.setName(productDTO.getName());
        product.setPrice(productDTO.getPrice());
        product.setDescription(productDTO.getDescription());

        // Newly created products have default stock of 0
        // If your database field does not allow null and has default value 0, this line can be omitted
        product.setStockQuantity(0);

        Product savedProduct = productRepository.save(product);
        logger.info("Product created successfully with id: {}", savedProduct.getId());

        // Note: convertToDTO here may get inventory from WarehouseService, so the returned DTO will still have inventory information
        return convertToDTO(savedProduct);
    }
    
    /**
     * Update product information
     */
    public ProductDTO updateProduct(Long productId, ProductDTO productDTO) {
        logger.info("Updating product: {}", productId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        // Only update product catalog information
        product.setName(productDTO.getName());
        product.setPrice(productDTO.getPrice());
        product.setDescription(productDTO.getDescription());

        // --- Key: Do not modify inventory here ---
        // Removed product.setStockQuantity(productDTO.getStockQuantity());

        Product savedProduct = productRepository.save(product);
        logger.info("Product updated successfully");

        return convertToDTO(savedProduct);
    }
    
    

    /**
     * Assign product to multiple warehouses
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Boolean assignProductToWarehouses(Long productId, AssignProductRequest request) {
        try {
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> {
                        logger.error("product not found with id: {}", productId);
                        return new RuntimeException("Product not found with id: " + productId);
                    });

            List<WarehouseProduct> warehouseProducts = new ArrayList<>();

            for (AssignProductRequest.WarehouseAssignment assignment : request.getAssignments()) {
                Warehouse warehouse = warehouseRepository.findById(assignment.getWarehouseId())
                        .orElseThrow(() -> {
                            logger.error("warehouse not found with id: {}", assignment.getWarehouseId());
                            return new RuntimeException("Warehouse not found with id: " + assignment.getWarehouseId());
                        });

                // check if warehouse have the product
                Optional<WarehouseProduct> existingWarehouseProduct = warehouseProductRepository
                        .findByWarehouseAndProduct(warehouse, product);

                WarehouseProduct warehouseProduct;

                if (existingWarehouseProduct.isPresent()) {
                    // if exists update quantity
                    warehouseProduct = existingWarehouseProduct.get();
                    int newQuantity = warehouseProduct.getQuantity() + assignment.getQuantity();
                    warehouseProduct.setQuantity(newQuantity);
                } else {
                    // not exists create new
                    warehouseProduct = new WarehouseProduct();
                    warehouseProduct.setProduct(product);
                    warehouseProduct.setWarehouse(warehouse);
                    warehouseProduct.setQuantity(assignment.getQuantity());
                }
                warehouseProduct.setModifyTime(LocalDateTime.now());
                warehouseProducts.add(warehouseProductRepository.save(warehouseProduct));
            }

            return true;
        } catch (Exception e) {
            logger.error("error assigning product {} to warehouses: {}", productId, e.getMessage());
            throw new RuntimeException("Error assigning product to warehouses", e);
        }
    }
    
    /**
     * Convert entity to DTO (using multi-warehouse aggregated inventory)
     */
    private ProductDTO convertToDTO(Product product) {
        ProductDTO dto = new ProductDTO();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setDescription(product.getDescription());
        // Use multi-warehouse aggregated inventory to replace Product table's stockQuantity
        int totalQuantity = warehouseService.getProductQuantity(product.getId());
        dto.setStockQuantity(totalQuantity);
        dto.setCreatedAt(product.getCreatedAt());
        dto.setUpdatedAt(product.getUpdatedAt());
        return dto;
    }
}
