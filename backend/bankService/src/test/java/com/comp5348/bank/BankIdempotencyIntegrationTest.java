package com.comp5348.bank;

import com.comp5348.bank.dto.RefundRequest;
import com.comp5348.bank.dto.RefundResponse;
import com.comp5348.bank.dto.TransferRequest;
import com.comp5348.bank.dto.TransferResponse;
import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.model.Transaction;
import com.comp5348.bank.repository.BankAccountRepository;
import com.comp5348.bank.repository.TransactionRepository;
import com.comp5348.bank.service.BankService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
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
 * 失败场景表 #2、#3、#10：bank 按幂等键去重。
 *
 * 【层】集成测试：真的 bank 代码 + 真的 Postgres（Testcontainers）。直接调 BankService，不经过 HTTP。
 * 【为什么一定要真数据库】去重靠的是 transactions.transaction_ref 的唯一约束，并发时靠数据库挡住第二个插入。
 *   H2 / Mockito 都测不出"两个请求同时到达"时数据库到底怎么表现。
 * 【字段名】规格要求 bank 的字段按 bank.proto 起名（idempotencyKey、originalTransactionId）。
 *   现在还叫 transactionRef / transactionId；绿灯阶段改名时，这里跟着改 setter，断言不变。
 */
@SpringBootTest(properties = {
        "DB_USERNAME=unused-overridden-by-testcontainers",
        "DB_PASSWORD=unused-overridden-by-testcontainers"
})
class BankIdempotencyIntegrationTest {

    // 一次性的 Postgres；@ServiceConnection 让 Spring 连它，而不是 application.properties 里的 localhost/webuser
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired BankService bankService;
    @Autowired BankAccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

    private static final BigDecimal START_BALANCE = new BigDecimal("1000.00");
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    /** 新开一个账户（每个测试用自己的账户，互不影响） */
    private String newAccount(BigDecimal balance) {
        BankAccount account = new BankAccount();
        account.setAccountNumber("ACC-T-" + UUID.randomUUID());
        account.setOwnerName("test");
        account.setBalance(balance);
        return accountRepository.save(account).getAccountNumber();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private TransferRequest transfer(String from, String to, String key) {
        TransferRequest request = new TransferRequest();
        request.setFromAccount(from);
        request.setToAccount(to);
        request.setAmount(PRICE);
        request.setTransactionRef(key); // 规格里叫 idempotencyKey
        return request;
    }

    /**
     * #2 bank 收到重复的键 → 返回第一次的结果，不再转账。
     * 预期 🟢：现有代码已经"先查再插"，这一条是安全网 —— 以后改代码时保证它不被改坏。
     */
    @Test
    void duplicateIdempotencyKey_returnsFirstResult() {
        String customer = newAccount(START_BALANCE);
        String store = newAccount(BigDecimal.ZERO);
        String key = "PAY-" + UUID.randomUUID() + "-1";

        // 同一个键发两次（store 超时后重试就是这样）
        TransferResponse first = bankService.transfer(transfer(customer, store, key));
        TransferResponse second = bankService.transfer(transfer(customer, store, key));

        // 断言 1：两次都成功，而且是同一笔交易（第二次只是把第一次的结果再说一遍）
        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
        // 断言 2：钱只动了一次
        assertThat(balanceOf(customer)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("100.00");
    }

    /**
     * #3 同一个键的两个请求同时到 bank → 只扣一次，两个都拿到成功。
     * 预期 ❔：读代码判断不出。"先查再插"在并发下可能两个都查到"没有"，然后都去扣钱、都去插入。
     * 并发问题不是每次都撞得上，所以用 @RepeatedTest 跑 5 次，每次 4 个线程同时发同一个键。
     */
    @RepeatedTest(5)
    void concurrentSameKey_onlyOneTransfer_bothSeeSuccess() throws Exception {
        String customer = newAccount(START_BALANCE);
        String store = newAccount(BigDecimal.ZERO);
        String key = "PAY-" + UUID.randomUUID() + "-1";
        int threads = 4;

        // CountDownLatch 当发令枪：所有线程就位后同时开跑，尽量让请求撞在一起
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<TransferResponse>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startGun.await();
                return bankService.transfer(transfer(customer, store, key));
            }));
        }
        startGun.countDown();

        // 收集结果：抛异常也算"这个请求没拿到成功"
        List<String> outcomes = new ArrayList<>();
        for (Future<TransferResponse> f : results) {
            try {
                TransferResponse r = f.get(30, TimeUnit.SECONDS);
                outcomes.add(r.isSuccess() ? "success" : "failed: " + r.getMessage());
            } catch (ExecutionException e) {
                outcomes.add("exception: " + e.getCause().getClass().getSimpleName());
            }
        }
        pool.shutdown();

        // 断言 1（钱）：只扣了一次
        assertThat(balanceOf(customer)).as("同一个键只能扣一次钱").isEqualByComparingTo("900.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("100.00");
        // 断言 2（回答）：每个请求都拿到"成功"。如果有一个拿到"失败"，store 会以为没扣钱，而钱其实已经扣了
        assertThat(outcomes).as("每个并发请求看到的结果").containsOnly("success");
    }

    /**
     * #10 同一笔退款请求到 bank 两次 → 只退一次。
     * 预期 ❔：现在的退款没有幂等键，每次都按原交易重新算一遍。第二次会不会再退一次钱？跑了才知道。
     * 规格要求：退款也按键去重（键 REFUND-{paymentId}），重复的请求返回第一次的结果。
     */
    @Test
    void duplicateRefundKey_refundsOnce() {
        String customer = newAccount(START_BALANCE);
        // 商店账户里还有别的顾客付的钱（真实情况）。如果是 0，第二次退款会因为"商店余额不足"碰巧失败，测不出问题
        String store = newAccount(new BigDecimal("5000.00"));
        TransferResponse paid = bankService.transfer(transfer(customer, store, "PAY-" + UUID.randomUUID() + "-1"));
        assertThat(paid.isSuccess()).as("前提：先成功扣了一笔").isTrue();

        // 同一笔退款发两次（store 退款超时后重试就是这样）
        RefundRequest refund = new RefundRequest();
        refund.setTransactionId(paid.getTransactionId()); // 规格里叫 originalTransactionId，键是 REFUND-{paymentId}
        refund.setReason("Order cancelled by user");
        RefundResponse first = bankService.refund(refund);
        String second;
        try {
            RefundResponse r = bankService.refund(refund);
            second = r.isSuccess() ? "success:" + r.getRefundTransactionId() : "failed: " + r.getMessage();
        } catch (RuntimeException e) {
            second = "exception: " + e.getClass().getSimpleName();
        }

        // 断言 1（钱）：只退了一次：顾客回到 1000，商店回到 5000
        assertThat(balanceOf(customer)).as("退两次就会变成 1100").isEqualByComparingTo("1000.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("5000.00");
        long refundRows = transactionRepository.findAll().stream()
                .filter(t -> t.getType() == Transaction.TransactionType.REFUND && t.getToAccount().equals(customer))
                .count();
        assertThat(refundRows).isEqualTo(1);
        // 断言 2（回答）：第二次也是"成功"，而且是同一笔退款。否则 store 会以为 bank 拒绝退款（#9），去叫人工
        assertThat(first.isSuccess()).isTrue();
        assertThat(second).as("重复的退款请求应返回第一次的结果").isEqualTo("success:" + first.getRefundTransactionId());
    }
}
