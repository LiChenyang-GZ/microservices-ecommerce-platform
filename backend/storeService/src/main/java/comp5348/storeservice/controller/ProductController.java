package comp5348.storeservice.controller;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.service.ProductService;
import comp5348.storeservice.utils.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class ProductController {
    
    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);
    
    @Autowired
    private ProductService productService;
    
    /**
     * 獲取所有商品列表
     * GET /api/products
     */
    @GetMapping
    public ResponseEntity<ProductResponse> getAllProducts() {
        logger.info("GET /api/products - Fetching all products");
        
        try {
            List<ProductDTO> products = productService.getAllProducts();
            return ResponseEntity.ok(ProductResponse.success(products, "Products retrieved successfully"));
        } catch (Exception e) {
            logger.error("Error fetching all products: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ProductResponse.error("Failed to fetch products: " + e.getMessage()));
        }
    }
    
    /**
     * 獲取有庫存的商品列表
     * GET /api/products/available
     */
    @GetMapping("/available")
    public ResponseEntity<ProductResponse> getAvailableProducts() {
        logger.info("GET /api/products/available - Fetching available products");
        
        try {
            List<ProductDTO> products = productService.getAvailableProducts();
            return ResponseEntity.ok(ProductResponse.success(products, "Available products retrieved successfully"));
        } catch (Exception e) {
            logger.error("Error fetching available products: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ProductResponse.error("Failed to fetch available products: " + e.getMessage()));
        }
    }
    
    /**
     * 根據ID獲取商品
     * GET /api/products/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long id) {
        logger.info("GET /api/products/{} - Fetching product by id", id);
        
        try {
            Optional<ProductDTO> product = productService.getProductById(id);
            if (product.isPresent()) {
                return ResponseEntity.ok(ProductResponse.success(product.get(), "Product found"));
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error fetching product by id {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ProductResponse.error("Failed to fetch product: " + e.getMessage()));
        }
    }
    
    /**
     * 根據名稱搜索商品
     * GET /api/products/search?name={name}
     */
    @GetMapping("/search")
    public ResponseEntity<ProductResponse> searchProductsByName(@RequestParam String name) {
        logger.info("GET /api/products/search?name={} - Searching products by name", name);
        
        try {
            List<ProductDTO> products = productService.searchProductsByName(name);
            return ResponseEntity.ok(ProductResponse.success(products, "Search results retrieved successfully"));
        } catch (Exception e) {
            logger.error("Error searching products by name {}: {}", name, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ProductResponse.error("Failed to search products: " + e.getMessage()));
        }
    }
    
    /**
     * 根據價格範圍搜索商品
     * GET /api/products/search/price?minPrice={minPrice}&maxPrice={maxPrice}
     */
    @GetMapping("/search/price")
    public ResponseEntity<ProductResponse> searchProductsByPriceRange(
            @RequestParam BigDecimal minPrice,
            @RequestParam BigDecimal maxPrice) {
        logger.info("GET /api/products/search/price?minPrice={}&maxPrice={} - Searching products by price range", 
                   minPrice, maxPrice);
        
        try {
            List<ProductDTO> products = productService.searchProductsByPriceRange(minPrice, maxPrice);
            return ResponseEntity.ok(ProductResponse.success(products, "Price range search results retrieved successfully"));
        } catch (Exception e) {
            logger.error("Error searching products by price range {} - {}: {}", minPrice, maxPrice, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ProductResponse.error("Failed to search products by price range: " + e.getMessage()));
        }
    }
    
    /**
     * 創建新商品
     * POST /api/products
     */
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@RequestBody ProductDTO productDTO) {
        logger.info("POST /api/products - Creating new product: {}", productDTO.getName());
        
        try {
            ProductDTO createdProduct = productService.createProduct(productDTO);
            return ResponseEntity.ok(ProductResponse.success(createdProduct, "Product created successfully"));
        } catch (Exception e) {
            logger.error("Error creating product: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ProductResponse.error("Failed to create product: " + e.getMessage()));
        }
    }
    
    /**
     * 更新商品資訊
     * PUT /api/products/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable Long id, @RequestBody ProductDTO productDTO) {
        logger.info("PUT /api/products/{} - Updating product", id);
        
        try {
            ProductDTO updatedProduct = productService.updateProduct(id, productDTO);
            return ResponseEntity.ok(ProductResponse.success(updatedProduct, "Product updated successfully"));
        } catch (Exception e) {
            logger.error("Error updating product {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(ProductResponse.error("Failed to update product: " + e.getMessage()));
        }
    }
    
    

    /**
     * 分配商品到多個倉庫
     * POST /api/products/{productId}/assign
     */
    @PostMapping("/{productId}/assign")
    public ResponseEntity<BaseResponse> assignProductToWarehouses(@PathVariable Long productId,
                                                                   @RequestBody AssignProductRequest request) {
        logger.info("POST /api/products/{}/assign - Assigning product to multiple warehouses", productId);
        
        try {
            Boolean assignResponse = productService.assignProductToWarehouses(productId, request);
            if (!assignResponse) {
                logger.warn("Failed to assign product {} to warehouses", productId);
                ProductResponse response = new ProductResponse(ResponseCode.A3.getMessage(), ResponseCode.A3.getResponseCode());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            BaseResponse response = new BaseResponse(ResponseCode.A2.getMessage(),
                    ResponseCode.A2.getResponseCode());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error assigning product {} to warehouses: {}", productId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(new BaseResponse(ResponseCode.A3.getMessage(), ResponseCode.A3.getResponseCode()));
        }
    }
}
