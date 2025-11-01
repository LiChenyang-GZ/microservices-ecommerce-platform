package com.comp5348.delivery.repository;

import com.comp5348.delivery.model.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * Find delivery by order ID
     */
    java.util.Optional<Delivery> findByOrderId(String orderId);
}
