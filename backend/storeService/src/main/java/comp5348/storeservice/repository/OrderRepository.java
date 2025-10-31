package comp5348.storeservice.repository;

import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    
    /**
     * 根據用戶ID查找訂單
     */
    List<Order> findByUserId(Long userId);
    
    /**
     * 根據訂單狀態查找訂單
     */
    List<Order> findByStatus(OrderStatus status);
    
//    /**
//     * 根據用戶ID和訂單狀態查找訂單
//     */
//    List<Order> findByUserIdAndStatus(Long userId, OrderStatus status);
    
//    /**
//     * 根據創建時間範圍查找訂單
//     */
//    @Query("SELECT o FROM Order o WHERE o.createdAt BETWEEN :startDate AND :endDate")
//    List<Order> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate,
//                                       @Param("endDate") LocalDateTime endDate);
    
//    /**
//     * 根據用戶ID和時間範圍查找訂單
//     */
//    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.createdAt BETWEEN :startDate AND :endDate")
//    List<Order> findByUserIdAndCreatedAtBetween(@Param("userId") Long userId,
//                                                @Param("startDate") LocalDateTime startDate,
//                                                @Param("endDate") LocalDateTime endDate);
    
//    /**
//     * 查找待付款的訂單
//     */
//    @Query("SELECT o FROM Order o WHERE o.status = 'PENDING_PAYMENT'")
//    List<Order> findPendingPaymentOrders();

    /**
     * 根据订单ID查找订单，并同时加载关联的商品信息。
     * 使用 LEFT JOIN FETCH 可以避免 N+1 查询问题，提升性能。
     * @param orderId 订单ID
     * @return 包含商品信息的订单Optional
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.product WHERE o.id = :orderId")
    Optional<Order> findByIdWithProduct(@Param("orderId") Long orderId);

    Optional<Order> findByDeliveryId(Long deliveryId);

}
