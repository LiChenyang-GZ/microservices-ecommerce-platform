package com.comp5348.delivery.repository;

import com.comp5348.delivery.model.DeliveryDeadLetterAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeliveryDeadLetterAlertRepository extends JpaRepository<DeliveryDeadLetterAlert, Long> {
}
