package comp5348.storeservice.dto;

import comp5348.storeservice.model.Warehouse;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class WarehouseResponse extends BaseResponse {
    private boolean success;
    private List<WarehouseDTO> warehouses;
    private List<Long> inventoryTransactionIds;
    private WarehouseDTO response;
    private Object data;  // Generic data field for other response types like List<InventoryAuditLogDTO>

    public WarehouseResponse(Warehouse warehouse, String message, String responseCode) {
        super(message, responseCode);
        this.success = true;
        if (warehouse != null) {
            this.response = new WarehouseDTO(warehouse);
        }
    }

    public WarehouseResponse(List<WarehouseDTO> warehouses, String message, String responseCode) {
        super(message, responseCode);
        this.success = true;
        if (warehouses != null) {
            this.warehouses = warehouses;
        }
    }

    public WarehouseResponse(WarehouseDTO warehouse, String message, String responseCode) {
        super(message, responseCode);
        this.success = true;
        if (warehouse != null) {
            this.response = warehouse;
        }
    }

    public WarehouseResponse(String message, String responseCode) {
        super(message, responseCode);
        this.success = true;
    }
    
    /**
     * Generic constructor for any data type (including List<InventoryAuditLogDTO>)
     * Use this when returning non-standard types
     */
    public static WarehouseResponse withData(Object data, String message, String responseCode) {
        WarehouseResponse response = new WarehouseResponse(message, responseCode);
        response.setData(data);
        response.setSuccess(true);
        return response;
    }
}



