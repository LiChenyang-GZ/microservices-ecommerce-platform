package comp5348.storeservice.repository;

import comp5348.storeservice.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    
    Optional<Payment> findByOrderId(Long orderId);
    
    Optional<Payment> findByBankTxnId(String bankTxnId);
    
    boolean existsByOrderId(Long orderId);
}


