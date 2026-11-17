package comp5348.storeservice.inventory;

import comp5348.storeservice.controller.WarehouseController;
import comp5348.storeservice.dto.ProductQuantityUpdateRequest;
import comp5348.storeservice.dto.ProductQuantityUpdateRequest.ProductQuantityUpdate;
import comp5348.storeservice.model.Product;
import comp5348.storeservice.model.Warehouse;
import comp5348.storeservice.model.WarehouseProduct;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员一次改好几行库存：PUT /api/warehouses/products/quantity。
 *
 * 【场景】管理员在后台把两个仓库里的库存一起改掉（一个请求里两条 update）。
 *
 * 【现在的代码】WarehouseService.updateProductQuantity 上有 @Async + @Transactional，方法体又包了一层
 * CompletableFuture.supplyAsync(...)：
 *   1. 循环里改完第一行就 return → 后面的行根本没改，接口却回"成功"。
 *   2. 真正干活的代码在 supplyAsync 的另一个线程里，@Transactional 管不到它（事务绑在调用它的线程上）。
 *      每次 save 自己提交 → 中途出错时，前面改过的行不会回滚。
 *
 * 【要的行为】一个请求里的所有行要么全改，要么全不改。
 *
 * 【层】集成测试：真的 Postgres（Testcontainers），直接调 controller 这个 bean（不走 HTTP 和登录，
 * 这里要测的是"改库存"本身）。
 */
class AdminStockUpdateIntegrationTest extends StoreIntegrationTest {

    @Autowired
    private WarehouseController warehouseController;

    /** 新建一个商品，放进一个新仓库，库存 = stock。 */
    private WarehouseProduct givenStock(int stock) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Product product = new Product();
        product.setName("Admin Item " + tag);
        product.setPrice(new BigDecimal("10.00"));
        product.setStockQuantity(0);
        product = productRepository.save(product);

        Warehouse warehouse = new Warehouse();
        warehouse.setName("Warehouse " + tag);
        warehouse.setLocation("Street " + tag);
        warehouse = warehouseRepository.save(warehouse);

        WarehouseProduct row = new WarehouseProduct();
        row.setWarehouse(warehouse);
        row.setProduct(product);
        row.setQuantity(stock);
        return warehouseProductRepository.save(row);
    }

    private ProductQuantityUpdate set(WarehouseProduct row, int newQuantity) {
        return new ProductQuantityUpdate(row.getWarehouse().getId(), row.getProduct().getId(), newQuantity);
    }

    private int quantityOf(WarehouseProduct row) {
        return warehouseProductRepository.findById(row.getId()).orElseThrow().getQuantity();
    }

    @Test
    void twoRowsInOneRequest_bothAreUpdated() {
        WarehouseProduct a = givenStock(5);
        WarehouseProduct b = givenStock(5);

        warehouseController.updateProductQuantities(new ProductQuantityUpdateRequest(List.of(set(a, 20), set(b, 30))));

        assertThat(quantityOf(a)).isEqualTo(20);
        assertThat(quantityOf(b)).as("the second row of the same request").isEqualTo(30);
    }

    @Test
    void secondRowDoesNotExist_firstRowIsNotChangedEither() {
        WarehouseProduct a = givenStock(5);
        ProductQuantityUpdate missing = new ProductQuantityUpdate(a.getWarehouse().getId(), -1L, 99);

        warehouseController.updateProductQuantities(new ProductQuantityUpdateRequest(List.of(set(a, 20), missing)));

        assertThat(quantityOf(a)).as("all or nothing: the request failed, so row a keeps its old quantity").isEqualTo(5);
    }
}
