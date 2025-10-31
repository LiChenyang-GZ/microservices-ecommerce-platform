package comp5348.storeservice.repository;

import comp5348.storeservice.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 查找所有有库存的商品 (用于向用户展示商品列表)
     */
    @Query("SELECT p FROM Product p WHERE p.stockQuantity > 0")
    List<Product> findAvailableProducts();

    /**
     * 根据ID查找一个商品，但前提是它必须有库存 (用于下单前的最终检查)
     */
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.stockQuantity > 0")
    Optional<Product> findAvailableById(@Param("id") Long id);

    /**
     * 更新指定商品的库存数量
     * 注意：这个方法不安全，高并发下会导致问题。
     * 更好的方法是在Service层使用乐观锁。但为了简化，我们先定义它。
     */
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = :quantity WHERE p.id = :productId")
    void updateStockQuantity(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 根據商品名稱查找商品
     */
    List<Product> findByNameContainingIgnoreCase(String name);

    /**
     * 根據價格範圍查找商品
     */
    @Query("SELECT p FROM Product p WHERE p.price BETWEEN :minPrice AND :maxPrice")
    List<Product> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice);
    
    
}
