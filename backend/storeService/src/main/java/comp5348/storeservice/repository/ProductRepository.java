package comp5348.storeservice.repository;

import comp5348.storeservice.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    
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
    
    /**
     * 查找有庫存的商品
     */
    @Query("SELECT p FROM Product p WHERE p.stockQuantity > 0")
    List<Product> findAvailableProducts();
    
    /**
     * 根據商品ID查找商品（包含庫存檢查）
     */
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.stockQuantity > 0")
    Optional<Product> findAvailableById(@Param("id") Long id);
    
    /**
     * 更新商品庫存
     */
    @Query("UPDATE Product p SET p.stockQuantity = :quantity WHERE p.id = :productId")
    void updateStockQuantity(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
