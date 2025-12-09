package comp5348.storeservice.repository;

import comp5348.storeservice.model.InventoryAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InventoryAuditLogRepository extends JpaRepository<InventoryAuditLog, Long> {
    
    /**
     * Query audit logs by order ID
     */
    List<InventoryAuditLog> findByOrderId(Long orderId);
    
    /**
     * Query audit logs by product ID
     */
    List<InventoryAuditLog> findByProductId(Long productId);
    
    /**
     * Query audit logs by warehouse ID
     */
    List<InventoryAuditLog> findByWarehouseId(Long warehouseId);
    
    /**
     * Query audit logs by operation type
     */
    List<InventoryAuditLog> findByOperationType(String operationType);
    
    /**
     * Query audit logs by order ID and operation type
     */
    List<InventoryAuditLog> findByOrderIdAndOperationType(Long orderId, String operationType);
    
    /**
     * Query audit logs by product ID, warehouse ID and operation type
     */
    @Query("SELECT a FROM InventoryAuditLog a WHERE a.productId = :productId AND a.warehouseId = :warehouseId AND a.operationType = :operationType")
    List<InventoryAuditLog> findByProductAndWarehouseAndOperationType(
            @Param("productId") Long productId,
            @Param("warehouseId") Long warehouseId,
            @Param("operationType") String operationType
    );
    
    /**
     * Query audit logs within a date range
     */
    @Query("SELECT a FROM InventoryAuditLog a WHERE a.operationTime BETWEEN :startTime AND :endTime")
    List<InventoryAuditLog> findByOperationTimeBetween(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
    
    /**
     * Query failed operations
     */
    @Query("SELECT a FROM InventoryAuditLog a WHERE a.status = 'FAILED'")
    List<InventoryAuditLog> findFailedOperations();
}
