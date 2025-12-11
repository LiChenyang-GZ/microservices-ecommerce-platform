package comp5348.storeservice.service;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.InventoryAuditLogRepository;
import comp5348.storeservice.repository.InventoryTransactionRepository;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.repository.WarehouseProductRepository;
import comp5348.storeservice.repository.WarehouseRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Transactional
public class WarehouseService {

    private static final Logger logger = LoggerFactory.getLogger(WarehouseService.class);

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private WarehouseProductRepository warehouseProductRepository;

    @Autowired
    private InventoryTransactionRepository inventoryTransactionRepository;
    
    @Autowired
    private InventoryAuditLogRepository auditLogRepository;
    
    @Autowired
    private ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<WarehouseDTO> getAllWarehouses() {
        List<Warehouse> warehouses = warehouseRepository.findAll();
        return warehouses.stream()
                .map(warehouse -> {
                    WarehouseDTO warehouseDTO = new WarehouseDTO(warehouse);
                    // Get all warehouse-product relationships for this warehouse
                    List<WarehouseProduct> warehouseProducts = warehouseProductRepository.findByWarehouseId(warehouse.getId());
                    // Convert to ProductDTOs with quantity information
                    List<ProductDTO> productDTOs = warehouseProducts.stream()
                            .map(wp -> {
                                ProductDTO productDTO = new ProductDTO();
                                productDTO.setId(wp.getProduct().getId());
                                productDTO.setName(wp.getProduct().getName());
                                productDTO.setPrice(wp.getProduct().getPrice());
                                productDTO.setDescription(wp.getProduct().getDescription());
                                productDTO.setStockQuantity(wp.getQuantity());  // Store quantity in stockQuantity field
                                return productDTO;
                            })
                            .collect(Collectors.toList());
                    warehouseDTO.setProducts(productDTOs);
                    return warehouseDTO;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public WarehouseDTO getWarehouseById(Long id) {
        return warehouseRepository.findById(id)
                .map(warehouse -> {
                    WarehouseDTO warehouseDTO = new WarehouseDTO(warehouse);
                    List<WarehouseProduct> warehouseProducts = warehouseProductRepository.findByWarehouseId(warehouse.getId());
                    List<ProductDTO> productDTOs = warehouseProducts.stream()
                            .map(wp -> {
                                ProductDTO productDTO = new ProductDTO();
                                productDTO.setId(wp.getProduct().getId());
                                productDTO.setName(wp.getProduct().getName());
                                productDTO.setPrice(wp.getProduct().getPrice());
                                productDTO.setDescription(wp.getProduct().getDescription());
                                productDTO.setStockQuantity(wp.getQuantity());
                                return productDTO;
                            })
                            .collect(Collectors.toList());
                    warehouseDTO.setProducts(productDTOs);
                    return warehouseDTO;
                })
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Integer getProductQuantity(Long productId) {
        return warehouseProductRepository.findTotalQuantityByProductId(productId).orElse(0);
    }

    public WarehouseDTO createWarehouse(WarehouseRequest request) {
        try {
            Warehouse warehouse = new Warehouse();
            warehouse.setName(request.getName());
            warehouse.setLocation(request.getLocation());
            warehouse.setModifyTime(LocalDateTime.now());
            Warehouse savedWarehouse = warehouseRepository.save(warehouse);
            return new WarehouseDTO(savedWarehouse);
        } catch (Exception e) {
            logger.error("An error occurred: {}", e.toString());
            return null;
        }
    }

    @Async
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public CompletableFuture<WarehouseProductDTO> updateProductQuantity(ProductQuantityUpdateRequest updates) {
        return CompletableFuture.supplyAsync(() -> {
            for (ProductQuantityUpdateRequest.ProductQuantityUpdate update : updates.getUpdates()) {
                try {
                    WarehouseProduct warehouseProduct = warehouseProductRepository
                            .findByWarehouseIdAndProductId(update.getWarehouseId(), update.getProductId())
                            .orElseThrow(() -> new EntityNotFoundException("WarehouseProduct not found"));

                    warehouseProduct.setQuantity(update.getNewQuantity());
                    warehouseProduct.setModifyTime(LocalDateTime.now());
                    WarehouseProduct savedWarehouseProduct = warehouseProductRepository.save(warehouseProduct);

                    InventoryTransaction transaction = new InventoryTransaction();
                    transaction.setProduct(savedWarehouseProduct.getProduct());
                    transaction.setWarehouse(savedWarehouseProduct.getWarehouse());
                    transaction.setQuantity(update.getNewQuantity());
                    transaction.setTransactionTime(LocalDateTime.now());
                    transaction.setType(InventoryTransactionType.IN);
                    inventoryTransactionRepository.save(transaction);

                    return new WarehouseProductDTO(warehouseProduct);
                } catch (OptimisticLockException e) {
                    logger.error("optimistic lock exception during quantity update: {}", e.getMessage());
                    throw e;
                }
            }
            return null;
        });
    }

    public WarehouseDTO updateWarehouse(Long id, Warehouse warehouseDetails) throws OptimisticLockException {
        return warehouseRepository.findByIdWithLock(id)
                .map(warehouse -> {
                    warehouse.setName(warehouseDetails.getName());
                    warehouse.setLocation(warehouseDetails.getLocation());
                    try {
                        warehouse.setModifyTime(LocalDateTime.now());
                        Warehouse updatedWarehouse = warehouseRepository.save(warehouse);
                        return new WarehouseDTO(updatedWarehouse);
                    } catch (OptimisticLockException e) {
                        logger.error("optimistic lock exception during warehouse update: {}", e.getMessage());
                        throw e;
                    } catch (Exception e) {
                        logger.error("failed to update warehouse: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    public Boolean deleteWarehouse(Long id) throws OptimisticLockException {
        return warehouseRepository.findByIdWithLock(id)
                .map(warehouse -> {
                    try {
                        warehouseRepository.delete(warehouse);
                        return true;
                    } catch (OptimisticLockException e) {
                        logger.error("optimistic lock exception during warehouse deletion: {}", e.getMessage());
                        return false;
                    } catch (Exception e) {
                        logger.error("failed to delete warehouse: {}", e.getMessage());
                        return false;
                    }
                })
                .orElse(false);
    }

    @Transactional // Ensure annotation is present
    public WarehouseDTO getAndUpdateAvailableWarehouse(long productId, int quantity, Long orderId) { // Suggest passing orderId
        logger.info("[WAREHOUSE] START: getAndUpdateAvailableWarehouse for productId={}, quantity={}, orderId={}", 
                productId, quantity, orderId);
        
        List<WarehouseProduct> availableProducts = warehouseProductRepository.findByProductIdAndQuantity(productId);
        logger.info("[WAREHOUSE] Found {} warehouses with available stock for productId={}", 
                availableProducts.size(), productId);

        // --- Step 1: Pure in-memory calculation and check ---
        int totalAvailable = availableProducts.stream().mapToInt(WarehouseProduct::getQuantity).sum();
        logger.info("[WAREHOUSE] Total available: {}, Requested: {}", totalAvailable, quantity);
        
        if (totalAvailable < quantity) {
            logger.warn("[WAREHOUSE] INSUFFICIENT STOCK for product: {}. Required: {}, Available: {}", 
                    productId, quantity, totalAvailable);
            recordAuditLog(productId, null, null, null, quantity, orderId, totalAvailable, totalAvailable, "HOLD", "FAILED", "Insufficient stock");
            return null; // Insufficient stock, return directly
        }

        int remainingQuantity = quantity;
        List<WarehouseProduct> productsToUpdate = new ArrayList<>();
        List<InventoryTransaction> transactionsToCreate = new ArrayList<>();
        List<WarehouseDTO> warehouseDTOs = new ArrayList<>(); // Used for return

        for (WarehouseProduct wp : availableProducts) {
            if (remainingQuantity <= 0) break;

            int quantityToTake = Math.min(wp.getQuantity(), remainingQuantity);
            int stockBefore = wp.getQuantity();
            logger.info("[WAREHOUSE] Taking {} units from warehouse {}, current stock before: {}", 
                    quantityToTake, wp.getWarehouse().getId(), wp.getQuantity());

            // Update inventory entity (only in memory)
            wp.setQuantity(wp.getQuantity() - quantityToTake);
            int stockAfter = wp.getQuantity();
            logger.info("[WAREHOUSE] Warehouse {} stock after deduction: {}", 
                    wp.getWarehouse().getId(), wp.getQuantity());
            
            productsToUpdate.add(wp);

            // Create transaction entity (only in memory)
            InventoryTransaction tx = createInventoryTransaction(wp, quantityToTake, InventoryTransactionType.HOLD);
            transactionsToCreate.add(tx);

            remainingQuantity -= quantityToTake;

            // Prepare return DTO
            warehouseDTOs.add(new WarehouseDTO(wp.getWarehouse()));
            
            // Record audit log for this warehouse operation
            recordAuditLog(wp.getProduct().getId(), wp.getProduct().getName(),
                    wp.getWarehouse().getId(), wp.getWarehouse().getName(),
                    quantityToTake, orderId, stockBefore, stockAfter, "HOLD", "SUCCESS", null);
        }

        // --- Step 2: Unified database write ---
        logger.info("[WAREHOUSE] Saving {} warehouse products to database", productsToUpdate.size());
        warehouseProductRepository.saveAll(productsToUpdate);
        
        logger.info("[WAREHOUSE] Saving {} inventory transactions to database", transactionsToCreate.size());
        List<InventoryTransaction> savedTxs = inventoryTransactionRepository.saveAll(transactionsToCreate);
        List<Long> inventoryTransactionIds = savedTxs.stream().map(InventoryTransaction::getId).collect(Collectors.toList());

        logger.info("[WAREHOUSE] COMPLETED: Created {} inventory transactions for product {}, orderId {}: {}", 
                inventoryTransactionIds.size(), productId, orderId, inventoryTransactionIds);

        // --- Step 3: Prepare and return response ---
        WarehouseDTO responseDTO = new WarehouseDTO();
        responseDTO.setWarehouses(warehouseDTOs);
        responseDTO.setInventoryTransactionIds(inventoryTransactionIds);
        return responseDTO;
    }

    @Transactional
    public boolean unholdProduct(UnholdProductRequest request) {
        List<Long> ids = request.getInventoryTransactionIds();
        if (ids == null || ids.isEmpty()) return false;

        logger.info("Unholding {} inventory transactions: {}", ids.size(), ids);

        try {
            List<InventoryTransaction> transactions = inventoryTransactionRepository.findAllById(ids);
            logger.info("Found {} transactions to unhold", transactions.size());

            int totalReleased = 0;
            for (InventoryTransaction tx : transactions) {
                // Skip if already released (UNHOLD)
                if (tx.getType() == InventoryTransactionType.UNHOLD) {
                    logger.info("Transaction {} already unheld, skipping", tx.getId());
                    continue;
                }

                // Only process HOLD transactions
                if (tx.getType() != InventoryTransactionType.HOLD) {
                    logger.warn("Transaction {} has unexpected type: {}, skipping", tx.getId(), tx.getType());
                    continue;
                }

                Optional<WarehouseProduct> wpOpt = warehouseProductRepository.findByWarehouseAndProduct(tx.getWarehouse(), tx.getProduct());
                if (!wpOpt.isPresent()) {
                    logger.error("WarehouseProduct not found for transaction {}", tx.getId());
                    continue;
                }
                WarehouseProduct wp = wpOpt.get();

                int stockBefore = wp.getQuantity();
                logger.info("Releasing {} units for transaction {}, warehouse product {} (current: {})",
                        tx.getQuantity(), tx.getId(), wp.getId(), wp.getQuantity());

                wp.setQuantity(wp.getQuantity() + tx.getQuantity());
                int stockAfter = wp.getQuantity();
                wp.setModifyTime(LocalDateTime.now());
                warehouseProductRepository.save(wp);

                tx.setType(InventoryTransactionType.UNHOLD);
                tx.setTransactionTime(LocalDateTime.now());
                inventoryTransactionRepository.save(tx);

                totalReleased += tx.getQuantity();
                
                // Record audit log for unhold operation
                recordAuditLog(tx.getProduct().getId(), tx.getProduct().getName(),
                        tx.getWarehouse().getId(), tx.getWarehouse().getName(),
                        tx.getQuantity(), tx.getId(), stockBefore, stockAfter, "UNHOLD", "SUCCESS", null);
            }

            logger.info("Total released: {} units", totalReleased);
            return true;
        } catch (Exception e) {
            logger.error("Error unholding inventory: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 创建库存交易记录的公共方法
     * @param wp 仓库产品
     * @param quantity 数量
     * @param type 交易类型（HOLD/UNHOLD/OUT/IN）
     * @return 创建的库存交易对象
     */
    private InventoryTransaction createInventoryTransaction(
            WarehouseProduct wp,
            int quantity,
            InventoryTransactionType type) {
        InventoryTransaction tx = new InventoryTransaction();
        tx.setProduct(wp.getProduct());
        tx.setWarehouse(wp.getWarehouse());
        tx.setQuantity(quantity);
        tx.setType(type);
        tx.setTransactionTime(LocalDateTime.now());
        return tx;
    }

    /**
     * Record OUT transaction for delivered or lost orders
     * This is an audit record to track when inventory officially leaves the system
     * Uses the same logic as getAndUpdateAvailableWarehouse to distribute across multiple warehouses
     * @param productId Product ID
     * @param quantity Quantity that left
     * @param orderId Order ID for reference
     * @return true if recording successful, false otherwise
     */
    @Transactional
    public boolean recordOutTransaction(Long productId, int quantity, Long orderId) {
        logger.info("Recording OUT transaction: productId={}, quantity={}, orderId={}", productId, quantity, orderId);
        
        try {
            // Get product info for audit log (in case of failure)
            Product product = productRepository.findById(productId).orElse(null);
            String productName = product != null ? product.getName() : null;
            
            // Get all warehouse products for this product (using same logic as hold)
            List<WarehouseProduct> availableProducts = warehouseProductRepository.findByProductIdAndQuantity(productId);
            
            if (availableProducts.isEmpty()) {
                logger.warn("No warehouse products found for productId={}", productId);
                recordAuditLog(productId, productName, null, null, quantity, orderId, 0, 0, "OUT", "FAILED", "No warehouse products found");
                return false;
            }
            
            // Create OUT transaction(s) for audit trail
            // Distribute quantity across multiple warehouses using same allocation logic
            int remainingQuantity = quantity;
            List<InventoryTransaction> transactionsToCreate = new ArrayList<>();
            int totalRecorded = 0;
            
            for (WarehouseProduct wp : availableProducts) {
                if (remainingQuantity <= 0) break;
                
                // Note: we don't modify warehouse stock here, just create audit record
                // The stock was already deducted during order creation (HOLD phase)
                int quantityToRecord = Math.min(wp.getQuantity(), remainingQuantity);
                if (quantityToRecord <= 0) continue;
                
                // Use public createInventoryTransaction method
                InventoryTransaction tx = createInventoryTransaction(wp, quantityToRecord, InventoryTransactionType.OUT);
                transactionsToCreate.add(tx);
                
                remainingQuantity -= quantityToRecord;
                totalRecorded += quantityToRecord;
                
                logger.debug("Recording {} units OUT from warehouse {} for orderId={}", 
                        quantityToRecord, wp.getWarehouse().getId(), orderId);
                
                // Record audit log for this specific warehouse operation
                recordAuditLog(wp.getProduct().getId(), wp.getProduct().getName(), 
                        wp.getWarehouse().getId(), wp.getWarehouse().getName(),
                        quantityToRecord, orderId, wp.getQuantity(), 
                        wp.getQuantity() - quantityToRecord, "OUT", "SUCCESS", null);
            }
            
            if (transactionsToCreate.isEmpty()) {
                logger.warn("No OUT transactions created for orderId={}", orderId);
                recordAuditLog(productId, productName, null, null, quantity, orderId, 0, 0, "OUT", "FAILED", "No quantity to record");
                return false;
            }
            
            inventoryTransactionRepository.saveAll(transactionsToCreate);
            logger.info("Created {} OUT transaction(s) for orderId={}, total quantity={}", 
                    transactionsToCreate.size(), orderId, totalRecorded);
            
            return true;
        } catch (Exception e) {
            logger.error("Error recording OUT transaction: {}", e.getMessage(), e);
            
            // Try to get product info for audit log
            Product product = productRepository.findById(productId).orElse(null);
            String productName = product != null ? product.getName() : null;
            recordAuditLog(productId, productName, null, null, quantity, orderId, 0, 0, "OUT", "FAILED", e.getMessage());
            return false;
        }
    }
    
    /**
     * Record inventory operation to audit log table
     */
    private void recordAuditLog(Long productId, String productName, Long warehouseId, String warehouseName,
                                Integer quantity, Long orderId, Integer stockBefore, Integer stockAfter,
                                String operationType, String status, String errorMessage) {
        try {
            InventoryAuditLog auditLog = new InventoryAuditLog();
            auditLog.setOperationType(operationType);
            auditLog.setProductId(productId);
            auditLog.setProductName(productName);
            auditLog.setWarehouseId(warehouseId);
            auditLog.setWarehouseName(warehouseName);
            auditLog.setQuantity(quantity);
            auditLog.setOrderId(orderId);
            auditLog.setStockBefore(stockBefore);
            auditLog.setStockAfter(stockAfter);
            auditLog.setOperationTime(LocalDateTime.now());
            auditLog.setReason("Order processing");
            auditLog.setStatus(status);
            auditLog.setErrorMessage(errorMessage);
            
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            logger.error("Failed to record audit log: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Query audit logs by order ID
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getAuditLogsByOrderId(Long orderId) {
        List<InventoryAuditLog> logs = auditLogRepository.findByOrderId(orderId);
        return logs.stream().map(InventoryAuditLogDTO::new).collect(Collectors.toList());
    }
    
    /**
     * Query audit logs by product ID
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getAuditLogsByProductId(Long productId) {
        List<InventoryAuditLog> logs = auditLogRepository.findByProductId(productId);
        return logs.stream().map(InventoryAuditLogDTO::new).collect(Collectors.toList());
    }
    
    /**
     * Query audit logs by warehouse ID
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getAuditLogsByWarehouseId(Long warehouseId) {
        List<InventoryAuditLog> logs = auditLogRepository.findByWarehouseId(warehouseId);
        return logs.stream().map(InventoryAuditLogDTO::new).collect(Collectors.toList());
    }
    
    /**
     * Query failed audit logs
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getFailedAuditLogs() {
        List<InventoryAuditLog> logs = auditLogRepository.findFailedOperations();
        return logs.stream().map(InventoryAuditLogDTO::new).collect(Collectors.toList());
    }
    
    /**
     * Query OUT operation audit logs only (for admin dashboard)
     * Shows warehouse outbound records when orders are delivered or lost
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getOutTransactionLogs() {
        List<InventoryAuditLog> logs = auditLogRepository.findByOperationType("OUT");
        return logs.stream().map(InventoryAuditLogDTO::new).collect(Collectors.toList());
    }
    
    /**
     * Query OUT operation audit logs by order ID (for admin dashboard)
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getOutTransactionLogsByOrderId(Long orderId) {
        List<InventoryAuditLog> logs = auditLogRepository.findByOrderIdAndOperationType(orderId, "OUT");
        return logs.stream().map(InventoryAuditLogDTO::new).collect(Collectors.toList());
    }
    
    /**
     * Query OUT operation audit logs by product ID (for admin dashboard)
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getOutTransactionLogsByProductId(Long productId) {
        // Filter OUT operations by product ID
        List<InventoryAuditLog> allLogs = auditLogRepository.findByProductId(productId);
        return allLogs.stream()
                .filter(log -> "OUT".equals(log.getOperationType()))
                .map(InventoryAuditLogDTO::new)
                .collect(Collectors.toList());
    }
    
    /**
     * Query OUT operation audit logs by warehouse ID (for admin dashboard)
     */
    @Transactional(readOnly = true)
    public List<InventoryAuditLogDTO> getOutTransactionLogsByWarehouseId(Long warehouseId) {
        // Filter OUT operations by warehouse ID
        List<InventoryAuditLog> allLogs = auditLogRepository.findByWarehouseId(warehouseId);
        return allLogs.stream()
                .filter(log -> "OUT".equals(log.getOperationType()))
                .map(InventoryAuditLogDTO::new)
                .collect(Collectors.toList());
    }
}


