package comp5348.storeservice.dto;

import comp5348.storeservice.model.Warehouse;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class WarehouseResponse extends BaseResponse {
    private List<WarehouseDTO> warehouses;
    private WarehouseDTO response;

    public WarehouseResponse(Warehouse warehouse, String message, String responseCode) {
        super(message, responseCode);
        if (warehouse != null) {
            this.response = new WarehouseDTO(warehouse);
        }
    }

    public WarehouseResponse(List<WarehouseDTO> warehouses, String message, String responseCode) {
        super(message, responseCode);
        if (warehouses != null) {
            this.warehouses = warehouses;
        }
    }

    public WarehouseResponse(WarehouseDTO warehouse, String message, String responseCode) {
        super(message, responseCode);
        if (warehouse != null) {
            this.response = warehouse;
        }
    }

    public WarehouseResponse(String message, String responseCode) {
        super(message, responseCode);
    }
}

