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
     * Find all products with stock (for displaying product list to users)
     */
    @Query("SELECT p FROM Product p WHERE p.stockQuantity > 0")
    List<Product> findAvailableProducts();

    /**
     * Find a product by ID, but only if it has stock (for final check before placing order)
     */
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.stockQuantity > 0")
    Optional<Product> findAvailableById(@Param("id") Long id);

    /**
     * Update stock quantity for specified product
     * Note: This method is unsafe and may cause issues under high concurrency.
     * A better approach is to use optimistic locking at the Service layer. But for simplicity, we define it first.
     */
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = :quantity WHERE p.id = :productId")
    void updateStockQuantity(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * Find products by product name
     */
    List<Product> findByNameContainingIgnoreCase(String name);

    /**
     * Find products by price range
     */
    @Query("SELECT p FROM Product p WHERE p.price BETWEEN :minPrice AND :maxPrice")
    List<Product> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice);
    
    
}
