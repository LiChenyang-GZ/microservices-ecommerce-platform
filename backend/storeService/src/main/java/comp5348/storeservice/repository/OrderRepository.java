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
     * Find orders by user ID, sorted by creation date descending (newest first)
     */
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    /**
     * Find orders by order status
     */
    List<Order> findByStatus(OrderStatus status);
    
//    /**
//     * Find orders by user ID and order status
//     */
//    List<Order> findByUserIdAndStatus(Long userId, OrderStatus status);
    
//    /**
//     * Find orders by creation time range
//     */
//    @Query("SELECT o FROM Order o WHERE o.createdAt BETWEEN :startDate AND :endDate")
//    List<Order> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate,
//                                       @Param("endDate") LocalDateTime endDate);
    
//    /**
//     * Find orders by user ID and time range
//     */
//    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.createdAt BETWEEN :startDate AND :endDate")
//    List<Order> findByUserIdAndCreatedAtBetween(@Param("userId") Long userId,
//                                                @Param("startDate") LocalDateTime startDate,
//                                                @Param("endDate") LocalDateTime endDate);
    
//    /**
//     * Find pending payment orders
//     */
//    @Query("SELECT o FROM Order o WHERE o.status = 'PENDING_PAYMENT'")
//    List<Order> findPendingPaymentOrders();

    /**
     * Find order by order ID and load associated product information at the same time.
     * Using LEFT JOIN FETCH can avoid N+1 query problem and improve performance.
     * @param orderId Order ID
     * @return Order Optional containing product information
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.product WHERE o.id = :orderId")
    Optional<Order> findByIdWithProduct(@Param("orderId") Long orderId);

    Optional<Order> findByDeliveryId(Long deliveryId);

}
