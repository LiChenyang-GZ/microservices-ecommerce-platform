package comp5348.storeservice.repository;

import comp5348.storeservice.model.PaymentOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentOutboxRepository extends JpaRepository<PaymentOutbox, Long> {
    
    List<PaymentOutbox> findByStatusAndRetryCountLessThan(String status, Integer maxRetries);
    
    List<PaymentOutbox> findByEventTypeAndStatus(String eventType, String status);
    
    List<PaymentOutbox> findByStatus(String status);
}


