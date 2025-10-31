package comp5348.storeservice.repository;

import comp5348.storeservice.model.Product;
import comp5348.storeservice.model.Warehouse;
import comp5348.storeservice.model.WarehouseProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WarehouseProductRepository extends JpaRepository<WarehouseProduct, Long> {

    @Query("select wp from WarehouseProduct wp where wp.product.id = :productId and wp.quantity > 0 order by wp.quantity desc")
    List<WarehouseProduct> findByProductIdAndQuantity(@Param("productId") Long productId);

    @Query("select sum(wp.quantity) from WarehouseProduct wp where wp.product.id = :productId")
    Optional<Integer> findTotalQuantityByProductId(@Param("productId") Long productId);

    List<WarehouseProduct> findByWarehouseId(Long warehouseId);

    Optional<WarehouseProduct> findByWarehouseAndProduct(Warehouse warehouse, Product product);

    @Query("select wp from WarehouseProduct wp where wp.warehouse.id = :warehouseId and wp.product.id = :productId")
    Optional<WarehouseProduct> findByWarehouseIdAndProductId(@Param("warehouseId") Long warehouseId, @Param("productId") Long productId);
}


