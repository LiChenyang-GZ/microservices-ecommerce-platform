package comp5348.storeservice.controller;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.Warehouse;
import comp5348.storeservice.service.WarehouseService;
import comp5348.storeservice.utils.ResponseCode;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    @Autowired
    private WarehouseService warehouseService;

    private static final Logger logger = LoggerFactory.getLogger(WarehouseController.class);

    @GetMapping
    public ResponseEntity<WarehouseResponse> getAllWarehouses() {
        logger.info("Received request to get all warehouses");
        List<WarehouseDTO> warehouseDTOS = warehouseService.getAllWarehouses();
        WarehouseResponse response = new WarehouseResponse(warehouseDTOS, ResponseCode.W7.getMessage(),
                ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WarehouseResponse> getWarehouseById(@PathVariable Long id) {
        logger.info("Received request to get warehouse with id: {}", id);
        WarehouseDTO warehouseDTO = warehouseService.getWarehouseById(id);
        if (warehouseDTO == null) {
            logger.warn("Warehouse not found with id: {}", id);
            WarehouseResponse response = new WarehouseResponse(ResponseCode.W3.getMessage(), ResponseCode.W3.getResponseCode());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        WarehouseResponse response = new WarehouseResponse(warehouseDTO, ResponseCode.W7.getMessage(),
                ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> createWarehouse(@RequestBody WarehouseRequest warehouse) {
        logger.info("Received request to create warehouse: {}", warehouse.getName());
        WarehouseDTO warehouseDTO = warehouseService.createWarehouse(warehouse);
        if (warehouseDTO == null) {
            logger.warn("Failed to create warehouse: {}", warehouse.getName());
            WarehouseResponse response = new WarehouseResponse(ResponseCode.W4.getMessage(), ResponseCode.W4.getResponseCode());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        WarehouseResponse response = new WarehouseResponse(warehouseDTO, ResponseCode.W0.getMessage(),
                ResponseCode.W0.getResponseCode());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<WarehouseResponse> updateWarehouse(@PathVariable Long id, @RequestBody Warehouse warehouseDetails) {
        logger.info("Received request to update warehouse with id: {}", id);
        try {
            WarehouseDTO warehouseDTO = warehouseService.updateWarehouse(id, warehouseDetails);
            if (warehouseDTO == null) {
                logger.warn("Failed to update warehouse with id: {}", id);
                WarehouseResponse response = new WarehouseResponse(ResponseCode.W5.getMessage(),
                        ResponseCode.W5.getResponseCode());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            WarehouseResponse response = new WarehouseResponse(warehouseDTO, ResponseCode.W2.getMessage(),
                    ResponseCode.W2.getResponseCode());
            return ResponseEntity.ok(response);
        } catch (OptimisticLockException e) {
            logger.warn("Optimistic lock exception when updating warehouse with id: {}", id);
            WarehouseResponse response = new WarehouseResponse(ResponseCode.W5.getMessage(),
                    ResponseCode.W5.getResponseCode());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }

    @PutMapping("/products/quantity")
    public ResponseEntity<BaseResponse> updateProductQuantities(@RequestBody ProductQuantityUpdateRequest updates) {
        try {
            CompletableFuture<WarehouseProductDTO> warehouseProductDTO = warehouseService.updateProductQuantity(updates);
            if (warehouseProductDTO.get() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new BaseResponse(ResponseCode.W5.getMessage(), ResponseCode.W5.getResponseCode()));
            }
            return ResponseEntity.ok().body(new WarehouseResponse(warehouseProductDTO.get().getWarehouse(),
                    ResponseCode.W1.getMessage(), ResponseCode.W1.getResponseCode()));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new BaseResponse(ResponseCode.W5.getMessage(), ResponseCode.W5.getResponseCode()));
        } catch (OptimisticLockException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new BaseResponse(ResponseCode.W5.getMessage(), ResponseCode.W5.getResponseCode()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new BaseResponse(ResponseCode.W5.getMessage(), ResponseCode.W5.getResponseCode()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<BaseResponse> deleteWarehouse(@PathVariable Long id) {
        logger.info("Received request to delete warehouse with id: {}", id);
        try {
            Boolean response = warehouseService.deleteWarehouse(id);
            if (!response) {
                logger.warn("Failed to delete warehouse with id: {}", id);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new BaseResponse(ResponseCode.W6.getMessage(),
                        ResponseCode.W6.getResponseCode()));
            }
            return ResponseEntity.ok(new BaseResponse(ResponseCode.W2.getMessage(),
                    ResponseCode.W2.getResponseCode()));
        } catch (OptimisticLockException e) {
            logger.warn("Optimistic lock exception when deleting warehouse with id: {}", id);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new BaseResponse(ResponseCode.W6.getMessage(),
                    ResponseCode.W6.getResponseCode()));
        }
    }

    @GetMapping("/available/{productId}/{quantity}")
    public ResponseEntity<WarehouseResponse> getAvailableWarehouse(@PathVariable long productId,
                                                                   @PathVariable int quantity) {
        // 1. Call Service method
        WarehouseDTO serviceResponse = warehouseService.getAndUpdateAvailableWarehouse(productId, quantity, null);

        // 2. Handle insufficient stock situation (service returns null)
        if (serviceResponse == null) {
            // Create a failure response indicating "insufficient stock"
            WarehouseResponse failureResponse = new WarehouseResponse(
                    ResponseCode.W8.getMessage(), ResponseCode.W8.getResponseCode());
            return ResponseEntity.ok(failureResponse);
        }

        // 3. [Core modification] Unpack data returned by Service and build final success response

        // Create an empty, successful WarehouseResponse object
        WarehouseResponse successResponse = new WarehouseResponse(
                ResponseCode.W7.getMessage(), ResponseCode.W7.getResponseCode());

        // Extract data from serviceResponse and populate into final successResponse
        successResponse.setWarehouses(serviceResponse.getWarehouses()); // Set warehouse list
        successResponse.setInventoryTransactionIds(serviceResponse.getInventoryTransactionIds()); // Set [key] transaction ID list

        // 4. Return success response with populated data
        return ResponseEntity.ok(successResponse);
    }

    @PutMapping("/unhold")
    public ResponseEntity<WarehouseResponse> unholdProduct(@RequestBody UnholdProductRequest request) {
        boolean result = warehouseService.unholdProduct(request);
        if (result) {
            return ResponseEntity.ok(new WarehouseResponse(
                    ResponseCode.W9.getMessage(), ResponseCode.W9.getResponseCode()));
        }
        WarehouseResponse response = new WarehouseResponse(
                ResponseCode.W10.getMessage(), ResponseCode.W10.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get inventory audit logs for an order
     */
    @GetMapping("/audit-logs/order/{orderId}")
    public ResponseEntity<WarehouseResponse> getAuditLogsByOrder(@PathVariable Long orderId) {
        logger.info("Received request to get audit logs for order: {}", orderId);
        List<InventoryAuditLogDTO> auditLogs = warehouseService.getAuditLogsByOrderId(orderId);
        WarehouseResponse response = WarehouseResponse.withData(auditLogs, "Audit logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get inventory audit logs for a product
     */
    @GetMapping("/audit-logs/product/{productId}")
    public ResponseEntity<WarehouseResponse> getAuditLogsByProduct(@PathVariable Long productId) {
        logger.info("Received request to get audit logs for product: {}", productId);
        List<InventoryAuditLogDTO> auditLogs = warehouseService.getAuditLogsByProductId(productId);
        WarehouseResponse response = WarehouseResponse.withData(auditLogs, "Audit logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get inventory audit logs for a warehouse
     */
    @GetMapping("/audit-logs/warehouse/{warehouseId}")
    public ResponseEntity<WarehouseResponse> getAuditLogsByWarehouse(@PathVariable Long warehouseId) {
        logger.info("Received request to get audit logs for warehouse: {}", warehouseId);
        List<InventoryAuditLogDTO> auditLogs = warehouseService.getAuditLogsByWarehouseId(warehouseId);
        WarehouseResponse response = WarehouseResponse.withData(auditLogs, "Audit logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get failed audit logs
     */
    @GetMapping("/audit-logs/failed")
    public ResponseEntity<WarehouseResponse> getFailedAuditLogs() {
        logger.info("Received request to get failed audit logs");
        List<InventoryAuditLogDTO> auditLogs = warehouseService.getFailedAuditLogs();
        WarehouseResponse response = WarehouseResponse.withData(auditLogs, "Failed audit logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    // ============ Admin Dashboard - OUT Transaction Logs ============
    
    /**
     * Admin: Get all OUT transaction logs (warehouse outbound records)
     * Shows all products that have left the warehouse when orders are delivered/lost
     */
    @GetMapping("/admin/out-transactions")
    public ResponseEntity<WarehouseResponse> getOutTransactions() {
        logger.info("Received admin request to get OUT transaction logs");
        List<InventoryAuditLogDTO> outLogs = warehouseService.getOutTransactionLogs();
        WarehouseResponse response = WarehouseResponse.withData(outLogs, "OUT transaction logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Admin: Get OUT transaction logs for a specific order
     * Shows which products left the warehouse for a specific order
     */
    @GetMapping("/admin/out-transactions/order/{orderId}")
    public ResponseEntity<WarehouseResponse> getOutTransactionsByOrder(@PathVariable Long orderId) {
        logger.info("Received admin request to get OUT transaction logs for order: {}", orderId);
        List<InventoryAuditLogDTO> outLogs = warehouseService.getOutTransactionLogsByOrderId(orderId);
        WarehouseResponse response = WarehouseResponse.withData(outLogs, "OUT transaction logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Admin: Get OUT transaction logs for a specific product
     * Shows which orders caused this product to leave the warehouse
     */
    @GetMapping("/admin/out-transactions/product/{productId}")
    public ResponseEntity<WarehouseResponse> getOutTransactionsByProduct(@PathVariable Long productId) {
        logger.info("Received admin request to get OUT transaction logs for product: {}", productId);
        List<InventoryAuditLogDTO> outLogs = warehouseService.getOutTransactionLogsByProductId(productId);
        WarehouseResponse response = WarehouseResponse.withData(outLogs, "OUT transaction logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Admin: Get OUT transaction logs for a specific warehouse
     * Shows all products that have left a specific warehouse
     */
    @GetMapping("/admin/out-transactions/warehouse/{warehouseId}")
    public ResponseEntity<WarehouseResponse> getOutTransactionsByWarehouse(@PathVariable Long warehouseId) {
        logger.info("Received admin request to get OUT transaction logs for warehouse: {}", warehouseId);
        List<InventoryAuditLogDTO> outLogs = warehouseService.getOutTransactionLogsByWarehouseId(warehouseId);
        WarehouseResponse response = WarehouseResponse.withData(outLogs, "OUT transaction logs retrieved successfully", ResponseCode.W7.getResponseCode());
        return ResponseEntity.ok(response);
    }

    /**
     * Get all OUT transaction logs (public endpoint for admin dashboard)
     */
    @GetMapping("/audit-logs/out-transactions")
    public ResponseEntity<WarehouseResponse> getAllOutTransactions() {
        logger.info("Received request to get all OUT transaction logs");
        try {
            List<InventoryAuditLogDTO> outLogs = warehouseService.getOutTransactionLogs();
            WarehouseResponse response = WarehouseResponse.withData(outLogs, "OUT transaction logs retrieved successfully", ResponseCode.W7.getResponseCode());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching OUT transaction logs: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(WarehouseResponse.withData(null, "Failed to fetch OUT transaction logs: " + e.getMessage(), "500"));
        }
    }
}


