package comp5348.storeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductQuantityUpdateRequest {
    private List<ProductQuantityUpdate> updates;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductQuantityUpdate {
        private Long warehouseId;
        private Long productId;
        private int newQuantity;
    }
}

