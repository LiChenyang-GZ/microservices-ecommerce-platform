package comp5348.storeservice.repository;

import comp5348.storeservice.model.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    @Lock(LockModeType.OPTIMISTIC)
    @Query("select w from Warehouse w where w.id = :id")
    Optional<Warehouse> findByIdWithLock(@Param("id") Long id);
}


