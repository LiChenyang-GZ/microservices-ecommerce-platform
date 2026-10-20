package com.comp5348.delivery.repository;

import com.comp5348.delivery.model.Delivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
    /**
     * Find all delivery tasks related to an email address.
     * Spring Data JPA will automatically generate query based on method name.
     * @param email Customer email
     * @return List of delivery tasks
     */
    List<Delivery> findByEmail(String email);

    /**
     * Find all delivery tasks related to an email address ordered by ID descending.
     * Useful for showing newest deliveries first.
     */
    List<Delivery> findByEmailOrderByIdDesc(String email);

    /**
     * Find delivery by order ID
     */
    java.util.Optional<Delivery> findByOrderId(String orderId);

    /** The row for this order, locked (SELECT ... FOR UPDATE) until the transaction ends: used by cancel-by-order */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.orderId = :orderId")
    java.util.Optional<Delivery> findByOrderIdForUpdate(@Param("orderId") String orderId);
}
