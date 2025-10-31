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
     * 獲取所有商品列表
     */
    public List<ProductDTO> getAllProducts() {
        logger.info("Fetching all products");
        return productRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * 獲取有庫存的商品列表
     */
    public List<ProductDTO> getAvailableProducts() {
        logger.info("Fetching available products");
        // 基于仓库聚合库存筛选（替代 Product.stockQuantity）
        return productRepository.findAll().stream()
                .map(this::convertToDTO)
                .filter(dto -> dto.getStockQuantity() != null && dto.getStockQuantity() > 0)
                .collect(Collectors.toList());
    }
    
    /**
     * 根據ID獲取商品
     */
    public Optional<ProductDTO> getProductById(Long productId) {
        logger.info("Fetching product by id: {}", productId);
        return productRepository.findById(productId)
                .map(this::convertToDTO);
    }
    
    /**
     * 根據名稱搜索商品
     */
    public List<ProductDTO> searchProductsByName(String name) {
        logger.info("Searching products by name: {}", name);
        return productRepository.findByNameContainingIgnoreCase(name).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * 根據價格範圍搜索商品
     */
    public List<ProductDTO> searchProductsByPriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        logger.info("Searching products by price range: {} - {}", minPrice, maxPrice);
        return productRepository.findByPriceRange(minPrice, maxPrice).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * 創建新商品
     */
    public ProductDTO createProduct(ProductDTO productDTO) {
        logger.info("Creating new product: {}", productDTO.getName());
        
        Product product = new Product();
        product.setName(productDTO.getName());
        product.setPrice(productDTO.getPrice());
        product.setDescription(productDTO.getDescription());
        product.setStockQuantity(productDTO.getStockQuantity());
        
        Product savedProduct = productRepository.save(product);
        logger.info("Product created successfully with id: {}", savedProduct.getId());
        
        return convertToDTO(savedProduct);
    }
    
    /**
     * 更新商品資訊
     */
    public ProductDTO updateProduct(Long productId, ProductDTO productDTO) {
        logger.info("Updating product: {}", productId);
        
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        
        product.setName(productDTO.getName());
        product.setPrice(productDTO.getPrice());
        product.setDescription(productDTO.getDescription());
        product.setStockQuantity(productDTO.getStockQuantity());
        
        Product savedProduct = productRepository.save(product);
        logger.info("Product updated successfully");
        
        return convertToDTO(savedProduct);
    }
    
    

    /**
     * 將商品分配到多個倉庫
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
     * 轉換實體為DTO（使用多仓聚合库存）
     */
    private ProductDTO convertToDTO(Product product) {
        ProductDTO dto = new ProductDTO();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setDescription(product.getDescription());
        // 使用多仓聚合库存替代Product表的stockQuantity
        int totalQuantity = warehouseService.getProductQuantity(product.getId());
        dto.setStockQuantity(totalQuantity);
        dto.setCreatedAt(product.getCreatedAt());
        dto.setUpdatedAt(product.getUpdatedAt());
        return dto;
    }
}
