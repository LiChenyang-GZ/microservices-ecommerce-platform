package comp5348.storeservice.repository;

import comp5348.storeservice.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    
    Optional<Payment> findByOrderId(Long orderId);
    
    Optional<Payment> findByBankTxnId(String bankTxnId);
    
    boolean existsByOrderId(Long orderId);

    /**
     * SELECT ... FOR UPDATE on the payment row. Everything that changes a payment's status goes through this,
     * so two requests (two Pay Now tabs, a bank result and a cancel) take turns instead of overwriting each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
