package comp5348.storeservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class AssignProductRequest {
    private List<WarehouseAssignment> assignments;

    @Data
    public static class WarehouseAssignment {
        private Long warehouseId;
        private Integer quantity;
    }
}

