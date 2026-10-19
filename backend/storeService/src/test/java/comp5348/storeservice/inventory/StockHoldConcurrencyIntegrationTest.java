package comp5348.storeservice.inventory;

import comp5348.storeservice.dto.CreateOrderRequest;
import comp5348.storeservice.model.Account;
import comp5348.storeservice.model.Product;
import comp5348.storeservice.model.Warehouse;
import comp5348.storeservice.model.WarehouseProduct;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一件商品，很多人同时下单：扣库存（HOLD）。
 *
 * 【场景】双十一：一个热门商品库存 10，8 个人几乎同时各买 1 件。库存够所有人，8 单都应该成功。
 *
 * 【现在的代码】WarehouseProduct 上有 @Version（乐观锁）。扣库存时先普通 SELECT 读出数量，
 * 在内存里减掉，提交时 UPDATE ... WHERE version = 读到的版本。
 * 两个人读到同一个版本，后提交的那个 UPDATE 改到 0 行 → Hibernate 抛 OptimisticLockException → 整单失败。
 * 不会超卖，但库存明明够，顾客却下单失败。热门商品冲突是常态，乐观锁就变成"大部分人失败"。
 *
 * 【要的行为】库存够，就全部成功：后来的人排队等前一个扣完，再读到新的数量接着扣。
 */
class StockHoldConcurrencyIntegrationTest extends StoreIntegrationTest {

    private static final int BUYERS = 8;

    /** 新建一个商品，放进一个仓库，库存 = stock。返回仓库库存行的 id。 */
    private record Stock(Long productId, Long warehouseProductId) {}

    private Stock givenProductWithStock(int stock) {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        Product product = new Product();
        product.setName("Hot Item " + tag);
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
        row = warehouseProductRepository.save(row);
        return new Stock(product.getId(), row.getId());
    }

    private Long newBuyer() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account buyer = new Account();
        buyer.setEmail("buyer-" + tag + "@test.local");
        buyer.setUsername("buyer-" + tag);
        buyer.setPassword("not-used");
        buyer.setBankAccountNumber("ACC-TEST-" + tag);
        return accountRepository.save(buyer).getId();
    }

    /** BUYERS 个人同时各买 1 件。返回成功的单数；失败的原因放进 errors。 */
    private int everyoneBuysOneAtOnce(Long productId, List<Throwable> errors) throws Exception {
        List<Long> buyers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) buyers.add(newBuyer());

        AtomicInteger succeeded = new AtomicInteger();
        // CountDownLatch 当"发令枪"：所有线程都准备好之后同时开跑，尽量让扣库存重叠
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
        for (Long buyerId : buyers) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    orderProductService.createOrderWithPayment(new CreateOrderRequest(buyerId,
                            List.of(new CreateOrderRequest.OrderItemRequest(productId, 1))));
                    succeeded.incrementAndGet();
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        return succeeded.get();
    }

    private int stockLeft(Stock stock) {
        return warehouseProductRepository.findById(stock.warehouseProductId()).orElseThrow().getQuantity();
    }

    /**
     * 红灯：库存够所有人，却有人失败。
     * 现在会红在第一个断言上，失败原因是 ObjectOptimisticLockingFailureException（乐观锁冲突）。
     */
    @Test
    void enoughStock_everyoneBuysAtOnce_allSucceed() throws Exception {
        Stock stock = givenProductWithStock(10);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        int succeeded = everyoneBuysOneAtOnce(stock.productId(), errors);

        assertThat(succeeded)
                .as("库存 10、%d 人各买 1 件，应该全部成功。失败原因：%s", BUYERS,
                        errors.stream().map(e -> e.getClass().getSimpleName() + ": " + e.getMessage()).toList())
                .isEqualTo(BUYERS);
        assertThat(stockLeft(stock)).as("扣掉 %d 件后还剩", BUYERS).isEqualTo(10 - BUYERS);
    }

    /**
     * 安全网（现在就是绿的，改完也必须是绿的）：库存不够时不能超卖。
     * 库存 3、8 人抢 → 正好 3 单成功，库存 0，不会变成负数。
     */
    @Test
    void notEnoughStock_everyoneBuysAtOnce_neverOversell() throws Exception {
        Stock stock = givenProductWithStock(3);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        everyoneBuysOneAtOnce(stock.productId(), errors);

        assertThat(stockLeft(stock)).as("库存不能变成负数").isGreaterThanOrEqualTo(0);
        int held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM inventory_transactions WHERE product_id = ?",
                Integer.class, stock.productId());
        assertThat(held).as("扣掉的总数 + 剩下的 = 原来的 3 件").isEqualTo(3 - stockLeft(stock));
    }
}
