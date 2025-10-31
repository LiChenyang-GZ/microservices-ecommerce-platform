package comp5348.storeservice.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProductResponse extends BaseResponse {
    private List<ProductDTO> products;
    private List<WarehouseProductDTO> warehouseProducts;
    private ProductDTO product;

    public ProductResponse(List<ProductDTO> products, String message, String responseCode) {
        super(message, responseCode);
        this.products = products;
    }

    public ProductResponse(List<WarehouseProductDTO> warehouseProductDTOs, String message, String responseCode, boolean isWarehouseProduct) {
        super(message, responseCode);
        this.warehouseProducts = warehouseProductDTOs;
    }

    public ProductResponse(ProductDTO product, String message, String responseCode) {
        super(message, responseCode);
        this.product = product;
    }

    public ProductResponse(String message, String responseCode) {
        super(message, responseCode);
    }

    // 静态工厂方法，用于向后兼容现有代码
    public static ProductResponse success(List<ProductDTO> products, String message) {
        ProductResponse response = new ProductResponse(products, message, "SUCCESS");
        return response;
    }

    public static ProductResponse success(ProductDTO product, String message) {
        ProductResponse response = new ProductResponse(product, message, "SUCCESS");
        return response;
    }

    public static ProductResponse error(String message) {
        ProductResponse response = new ProductResponse(message, "ERROR");
        return response;
    }
}
