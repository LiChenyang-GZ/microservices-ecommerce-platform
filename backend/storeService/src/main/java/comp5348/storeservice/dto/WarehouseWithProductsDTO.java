package comp5348.storeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Warehouse with Product Inventory DTO
 * Shows warehouse details along with all products it holds and their quantities
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseWithProductsDTO {
    private Long warehouseId;
    private String warehouseName;
    private String location;
    private Integer totalProductsCount;  // Total unique products in this warehouse
    private Integer totalQuantity;       // Total quantity of all products
    private List<WarehouseProductInventoryDTO> products;  // List of products with quantities

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WarehouseProductInventoryDTO {
        private Long productId;
        private String productName;
        private Integer quantity;
        private java.math.BigDecimal price;
    }
}
