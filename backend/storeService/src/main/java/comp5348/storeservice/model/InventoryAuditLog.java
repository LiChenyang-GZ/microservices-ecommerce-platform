package comp5348.storeservice.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Inventory Audit Log - Records all inventory operations for audit trail
 * Tracks HOLD, UNHOLD, OUT, and IN transactions with detailed information
 */
@Entity
@Table(name = "inventory_audit_log")
public class InventoryAuditLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Operation type: HOLD, UNHOLD, OUT, IN
    @Column(name = "operation_type", nullable = false)
    private String operationType;
    
    // Product information
    @Column(name = "product_id", nullable = false)
    private Long productId;
    
    @Column(name = "product_name")
    private String productName;
    
    // Warehouse information
    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;
    
    @Column(name = "warehouse_name")
    private String warehouseName;
    
    // Quantity involved
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
    
    // Order reference (if applicable)
    @Column(name = "order_id")
    private Long orderId;
    
    // Status before operation
    @Column(name = "stock_before")
    private Integer stockBefore;
    
    // Status after operation
    @Column(name = "stock_after")
    private Integer stockAfter;
    
    // Operation timestamp
    @Column(name = "operation_time", nullable = false)
    private LocalDateTime operationTime;
    
    // Additional context/reason
    @Column(name = "reason", length = 255)
    private String reason;
    
    // Status: SUCCESS, FAILED
    @Column(name = "status", nullable = false)
    private String status;
    
    // Error message (if operation failed)
    @Column(name = "error_message", length = 500)
    private String errorMessage;
    
    // Created timestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getOperationType() {
        return operationType;
    }
    
    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }
    
    public Long getProductId() {
        return productId;
    }
    
    public void setProductId(Long productId) {
        this.productId = productId;
    }
    
    public String getProductName() {
        return productName;
    }
    
    public void setProductName(String productName) {
        this.productName = productName;
    }
    
    public Long getWarehouseId() {
        return warehouseId;
    }
    
    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }
    
    public String getWarehouseName() {
        return warehouseName;
    }
    
    public void setWarehouseName(String warehouseName) {
        this.warehouseName = warehouseName;
    }
    
    public Integer getQuantity() {
        return quantity;
    }
    
    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
    
    public Long getOrderId() {
        return orderId;
    }
    
    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }
    
    public Integer getStockBefore() {
        return stockBefore;
    }
    
    public void setStockBefore(Integer stockBefore) {
        this.stockBefore = stockBefore;
    }
    
    public Integer getStockAfter() {
        return stockAfter;
    }
    
    public void setStockAfter(Integer stockAfter) {
        this.stockAfter = stockAfter;
    }
    
    public LocalDateTime getOperationTime() {
        return operationTime;
    }
    
    public void setOperationTime(LocalDateTime operationTime) {
        this.operationTime = operationTime;
    }
    
    public String getReason() {
        return reason;
    }
    
    public void setReason(String reason) {
        this.reason = reason;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
