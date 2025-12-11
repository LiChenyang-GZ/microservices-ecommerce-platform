package comp5348.storeservice.controller;

import comp5348.storeservice.dto.InventoryAuditLogDTO;
import comp5348.storeservice.dto.WarehouseResponse;
import comp5348.storeservice.service.WarehouseService;
import comp5348.storeservice.utils.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Product Inventory Controller
 * Handles product-level inventory records and transactions
 */
@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class ProductInventoryController {
    
    private static final Logger logger = LoggerFactory.getLogger(ProductInventoryController.class);
    
    @Autowired
    private WarehouseService warehouseService;
    
    /**
     * Get product inventory records (inbound/outbound)
     * Shows all inventory transactions for a specific product across all warehouses
     * 
     * GET /api/products/{productId}/inventory-records
     */
    @GetMapping("/{productId}/inventory-records")
    public ResponseEntity<WarehouseResponse> getProductInventoryRecords(@PathVariable Long productId) {
        logger.info("Received request to get inventory records for product: {}", productId);
        try {
            List<InventoryAuditLogDTO> records = warehouseService.getOutTransactionLogsByProductId(productId);
            WarehouseResponse response = WarehouseResponse.withData(records, 
                    "Product inventory records retrieved successfully", 
                    ResponseCode.W7.getResponseCode());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching product inventory records: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(WarehouseResponse.withData(null, 
                            "Failed to fetch product inventory records: " + e.getMessage(), 
                            "500"));
        }
    }
}
