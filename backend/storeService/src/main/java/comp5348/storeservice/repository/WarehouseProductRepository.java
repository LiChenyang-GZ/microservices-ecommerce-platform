package comp5348.storeservice.repository;

import comp5348.storeservice.model.Product;
import comp5348.storeservice.model.Warehouse;
import comp5348.storeservice.model.WarehouseProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WarehouseProductRepository extends JpaRepository<WarehouseProduct, Long> {

    @Query("select wp from WarehouseProduct wp where wp.product.id = :productId and wp.quantity > 0 order by wp.quantity asc")
    List<WarehouseProduct> findByProductIdAndQuantity(@Param("productId") Long productId);

    /**
     * SELECT ... FOR UPDATE: lock this product's stock rows until the transaction ends, so concurrent orders
     * queue up instead of failing on @Version. Ordered by id so two orders always lock rows in the same order
     * (no deadlock). After waiting, Postgres re-reads the row, so the quantity is the latest committed one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wp from WarehouseProduct wp where wp.product.id = :productId and wp.quantity > 0 order by wp.id")
    List<WarehouseProduct> findByProductIdForUpdate(@Param("productId") Long productId);

    @Query("select sum(wp.quantity) from WarehouseProduct wp where wp.product.id = :productId")
    Optional<Integer> findTotalQuantityByProductId(@Param("productId") Long productId);

    List<WarehouseProduct> findByWarehouseId(Long warehouseId);

    Optional<WarehouseProduct> findByWarehouseAndProduct(Warehouse warehouse, Product product);

    @Query("select wp from WarehouseProduct wp where wp.warehouse.id = :warehouseId and wp.product.id = :productId")
    Optional<WarehouseProduct> findByWarehouseIdAndProductId(@Param("warehouseId") Long warehouseId, @Param("productId") Long productId);
}


