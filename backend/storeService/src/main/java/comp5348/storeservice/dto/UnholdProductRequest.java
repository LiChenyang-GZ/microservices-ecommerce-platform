package comp5348.storeservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class UnholdProductRequest {
    private List<Long> inventoryTransactionIds;
}


