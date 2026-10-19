package comp5348.storeservice.inventory;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.service.WarehouseService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取消订单时还库存（unhold），刚好有人在买同一个商品。
 *
 * 【场景】顾客 A 取消订单，要把 1 件还回库存。同一时刻顾客 B 在下单买同一个商品。
 *
 * 【现在的代码】下单扣库存已经是 SELECT ... FOR UPDATE（PR #13）。
 * 但 WarehouseService.unholdProduct 还是普通 SELECT 读出数量 → 内存里 +1 → 提交时带 @Version 写回。
 * 它读到的是 B 提交之前的版本号；B 一提交版本号就变了，unhold 的 UPDATE 改到 0 行 → 乐观锁冲突。
 *
 * 【要的行为】取消成功，库存也还回去了：B 扣的 1 件和 A 还的 1 件都算上。
 *
 * 【怎么让两件事"刚好撞上"，而且每次都撞上】不靠多线程碰运气，而是排好顺序：
 *   1. 线程 B 走真实的扣库存代码，锁住库存行，然后**停住不提交**。
 *   2. 线程 A 取消订单。unhold 的普通 SELECT 不用等锁，读到 B 提交前的数量和版本号；
 *      提交时它的 UPDATE 要等 B 的行锁，于是卡住。
 *   3. 测试看到 A 在等锁（查 pg_stat_activity），再放 B 提交。
 *   4. A 的 UPDATE 继续执行，发现版本号已经变了。
 */
class UnholdConcurrencyIntegrationTest extends StoreIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private WarehouseService warehouseService;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    void cancelWhileSomeoneBuysSameProduct_cancelSucceedsAndStockIsReturned() throws Exception {
        // 顾客 A 的订单：库存 100，下单后 99
        PlacedOrder orderA = placeOrder();
        Long productId = warehouseProductRepository.findById(orderA.warehouseProductId()).orElseThrow()
                .getProduct().getId();
        assertThat(stockOf(orderA)).as("前提：A 下单后库存").isEqualTo(99);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch bHoldsLock = new CountDownLatch(1);
        CountDownLatch letBCommit = new CountDownLatch(1);

        // 1. 顾客 B 下单扣 1 件：真实的扣库存代码，在一个事务里锁住库存行，停住不提交
        Future<?> buyerB = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            warehouseService.getAndUpdateAvailableWarehouse(productId, 1, null);
            bHoldsLock.countDown();
            try {
                assertThat(letBCommit.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(bHoldsLock.await(30, TimeUnit.SECONDS)).as("B 已经锁住库存行").isTrue();

        // 2. 顾客 A 取消订单（另一个线程，因为它会卡在等锁上）
        CompletableFuture<Throwable> cancelA = CompletableFuture.supplyAsync(() -> {
            try {
                orderService.cancelOrder(orderA.orderId());
                return null;
            } catch (Throwable e) {
                return e;
            }
        }, threads);

        // 3. 等到 A 真的卡在等锁上，再放 B 提交
        waitUntilSomeoneWaitsForRowLock();
        letBCommit.countDown();
        buyerB.get(30, TimeUnit.SECONDS);

        // 4. 看 A 的取消怎么结束
        Throwable cancelError = cancelA.get(30, TimeUnit.SECONDS);
        threads.shutdown();

        assertThat(cancelError).as("取消应该成功，实际抛了：%s", cancelError).isNull();
        assertThat(orderOf(orderA).getStatus()).as("A 的订单已取消").isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(orderA)).as("99 − B 买走 1 + A 还回 1").isEqualTo(99);
    }

    private int stockOf(PlacedOrder order) {
        return warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity();
    }

    /** pg_stat_activity：Postgres 里每个连接正在做什么。wait_event_type = 'Lock' 表示它在等行锁。 */
    private void waitUntilSomeoneWaitsForRowLock() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("10 秒内没有看到 A 在等锁：取消的 UPDATE 没有撞上 B 的行锁，测试的前提不成立");
    }
}
