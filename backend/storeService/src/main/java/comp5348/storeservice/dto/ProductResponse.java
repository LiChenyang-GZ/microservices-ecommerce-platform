package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
    
    private boolean success;
    private String message;
    private List<ProductDTO> products;
    private ProductDTO product;
    
    public static ProductResponse success(List<ProductDTO> products, String message) {
        return new ProductResponse(true, message, products, null);
    }
    
    public static ProductResponse success(ProductDTO product, String message) {
        return new ProductResponse(true, message, null, product);
    }
    
    public static ProductResponse error(String message) {
        return new ProductResponse(false, message, null, null);
    }
}
