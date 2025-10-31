package comp5348.storeservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import comp5348.storeservice.model.Warehouse;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WarehouseDTO {
    private Long id;
    private String name;
    private String location;
    private List<ProductDTO> products;
    private List<WarehouseDTO> warehouses = new ArrayList<>();
    private List<Long> inventoryTransactionIds = new ArrayList<>();

    public WarehouseDTO(Warehouse warehouse) {
        this.id = warehouse.getId();
        this.name = warehouse.getName();
        this.location = warehouse.getLocation();
    }
}


