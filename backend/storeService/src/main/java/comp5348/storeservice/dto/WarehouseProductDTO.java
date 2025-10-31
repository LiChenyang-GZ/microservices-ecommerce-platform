package comp5348.storeservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import comp5348.storeservice.model.Product;
import comp5348.storeservice.model.Warehouse;
import comp5348.storeservice.model.WarehouseProduct;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WarehouseProductDTO {
    private Long id;
    private Warehouse warehouse;
    private Product product;
    private Integer quantity;

    public WarehouseProductDTO(WarehouseProduct warehouseProduct) {
        this.id = warehouseProduct.getId();
        this.warehouse = warehouseProduct.getWarehouse();
        this.product = warehouseProduct.getProduct();
        this.quantity = warehouseProduct.getQuantity();
    }
}

