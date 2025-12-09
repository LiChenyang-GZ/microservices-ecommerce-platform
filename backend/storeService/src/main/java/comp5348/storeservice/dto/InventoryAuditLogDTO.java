package comp5348.storeservice.dto;

import comp5348.storeservice.model.InventoryAuditLog;
import java.time.LocalDateTime;

/**
 * DTO for InventoryAuditLog
 */
public class InventoryAuditLogDTO {
    
    private Long id;
    private String operationType;
    private Long productId;
    private String productName;
    private Long warehouseId;
    private String warehouseName;
    private Integer quantity;
    private Long orderId;
    private Integer stockBefore;
    private Integer stockAfter;
    private LocalDateTime operationTime;
    private String reason;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    
    public InventoryAuditLogDTO() {}
    
    public InventoryAuditLogDTO(InventoryAuditLog auditLog) {
        this.id = auditLog.getId();
        this.operationType = auditLog.getOperationType();
        this.productId = auditLog.getProductId();
        this.productName = auditLog.getProductName();
        this.warehouseId = auditLog.getWarehouseId();
        this.warehouseName = auditLog.getWarehouseName();
        this.quantity = auditLog.getQuantity();
        this.orderId = auditLog.getOrderId();
        this.stockBefore = auditLog.getStockBefore();
        this.stockAfter = auditLog.getStockAfter();
        this.operationTime = auditLog.getOperationTime();
        this.reason = auditLog.getReason();
        this.status = auditLog.getStatus();
        this.errorMessage = auditLog.getErrorMessage();
        this.createdAt = auditLog.getCreatedAt();
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
