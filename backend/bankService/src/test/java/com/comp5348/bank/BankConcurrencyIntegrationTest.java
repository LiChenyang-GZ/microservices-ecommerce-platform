package com.comp5348.bank;

import com.comp5348.bank.dto.TransferRequest;
import com.comp5348.bank.dto.TransferResponse;
import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.repository.BankAccountRepository;
import com.comp5348.bank.service.BankService;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 #16：同一个账户同时进行两笔（这里是 10 笔）不同键的转账 → 不能丢扣款。
 *
 * 【层】集成测试：真的 bank 代码 + 真的 Postgres（Testcontainers）。
 * 【问题】BankService.transfer 是"读余额 → 在 Java 内存里减 → 写回去"。账户行没有锁，也没有 @Version。
 *   两个事务同时读到 1000，各减 50，各写回 950：本来应该是 900，丢了一笔扣款。这叫"丢失更新"（lost update，八股 E3）。
 * 【为什么要真数据库】丢失更新是数据库事务并发时才出现的现象，Mockito / H2 测不出来。
 * 【修法（Christy 已定）】转账、退款时对涉及的账户行 SELECT … FOR UPDATE，按账号排序加锁，防止两笔反方向的转账互相等待（死锁）。
 */
@SpringBootTest(properties = {
        "DB_USERNAME=unused-overridden-by-testcontainers",
        "DB_PASSWORD=unused-overridden-by-testcontainers",
        // gRPC server on a random free port: never clashes with a bank already running on 9090
        "bank.grpc.port=0"
})
class BankConcurrencyIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired BankService bankService;
    @Autowired BankAccountRepository accountRepository;

    private String newAccount(String balance) {
        BankAccount account = new BankAccount();
        account.setAccountNumber("ACC-T-" + UUID.randomUUID());
        account.setOwnerName("test");
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account).getAccountNumber();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    /**
     * 10 个线程同时从同一个顾客账户转 50 元给商店，每笔都是不同的幂等键（10 个不同的订单）。
     * 并发问题不是每次都撞得上，所以 @RepeatedTest 跑 3 次。
     */
    @RepeatedTest(3)
    void concurrentTransfersSameAccount_noLostUpdate() throws Exception {
        String customer = newAccount("1000.00");
        String store = newAccount("0.00");
        int transfers = 10;

        // CountDownLatch 当发令枪：10 个线程就位后同时开跑
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(transfers);
        List<Future<TransferResponse>> results = new ArrayList<>();
        for (int i = 0; i < transfers; i++) {
            TransferRequest request = new TransferRequest();
            request.setFromAccount(customer);
            request.setToAccount(store);
            request.setAmount(new BigDecimal("50.00"));
            request.setIdempotencyKey("PAY-" + UUID.randomUUID() + "-1"); // 每笔不同的键：不是重复请求，是 10 笔真的订单
            results.add(pool.submit(() -> {
                startGun.await();
                return bankService.transfer(request);
            }));
        }
        startGun.countDown();

        int succeeded = 0;
        for (Future<TransferResponse> f : results) {
            if (f.get(30, TimeUnit.SECONDS).isSuccess()) succeeded++;
        }
        pool.shutdown();

        // 前提：10 笔都成功（余额足够，1000 ≥ 10 × 50）
        assertThat(succeeded).isEqualTo(transfers);
        // 断言（核心）：10 笔成功，就要真的扣了 10 × 50 = 500。丢一笔，顾客就多出 50，商店少收 50
        assertThat(balanceOf(customer)).as("顾客：1000 - 10×50").isEqualByComparingTo("500.00");
        assertThat(balanceOf(store)).as("商店：0 + 10×50").isEqualByComparingTo("500.00");
    }
}
