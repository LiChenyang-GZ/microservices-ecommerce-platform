package com.comp5348.bank;

import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.repository.BankAccountRepository;
import com.comp5348.bank.repository.TransactionRepository;
import com.comp5348.bank.service.BankService;
import com.comp5348.contracts.bank.v1.BankServiceGrpc;
import com.comp5348.contracts.bank.v1.Outcome;
import com.comp5348.contracts.bank.v1.RefundRequest;
import com.comp5348.contracts.bank.v1.RefundResponse;
import com.comp5348.contracts.bank.v1.TransferRequest;
import com.comp5348.contracts.bank.v1.TransferResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * bank 的 gRPC 接口（ADR-10：store 调 bank 用 gRPC）。失败场景表（Obsidian 18 号文件）第 23–29 行。
 *
 * 【层】集成测试：真的 bank（Spring + 真 Postgres），测试自己当 gRPC 客户端，通过真的网络调 bank。
 * 【红灯为什么是红】bank 现在只会 HTTP，没有人在 gRPC 端口上听 → 每个调用都是 UNAVAILABLE。
 *   测试能编译，是因为客户端代码（BankServiceGrpc）是从 contracts 里的 bank.proto 生成的，第 0 波就有了。
 * 【怎么读结果】bank.proto 的约定：状态 OK + outcome 才是 bank 的回答；业务上的"不"是 OK + REJECTED，
 *   绝不能用 gRPC 错误码表示（store 会把错误码当成"不知道"，一直重试）。
 */
@SpringBootTest(properties = {
        "DB_USERNAME=unused-overridden-by-testcontainers",
        "DB_PASSWORD=unused-overridden-by-testcontainers"
})
class BankGrpcIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** bank 的 gRPC 端口：测试挑一个空闲端口告诉 bank（真部署是 BANK_GRPC_PORT=9090） */
    static final int GRPC_PORT = freePort();

    /** 测试这边的 gRPC 客户端连接。usePlaintext：不用 TLS，和 compose 里 store → bank 一样 */
    static final ManagedChannel CHANNEL = NettyChannelBuilder.forAddress("localhost", GRPC_PORT).usePlaintext().build();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void grpcPort(DynamicPropertyRegistry registry) {
        registry.add("bank.grpc.port", () -> GRPC_PORT);
    }

    @AfterAll
    static void closeChannel() {
        CHANNEL.shutdownNow();
    }

    @Autowired BankAccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired TransactionTemplate transactionTemplate;
    // "间谍"：真的 BankService，只有第 27 行的测试让它抛异常。每个测试结束后 Spring 自动把剧本清掉
    @MockitoSpyBean BankService bankService;

    // ---------- 小工具 ----------

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 每次调用都现设一个 10 秒的 deadline（设在 stub 上只管这一次调用） */
    private static BankServiceGrpc.BankServiceBlockingStub bank() {
        return BankServiceGrpc.newBlockingStub(CHANNEL).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private String newAccount(String balance) {
        BankAccount account = new BankAccount();
        account.setAccountNumber("ACC-G-" + UUID.randomUUID());
        account.setOwnerName("grpc-test");
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account).getAccountNumber();
    }

    private BigDecimal balanceOf(String account) {
        return accountRepository.findByAccountNumber(account).orElseThrow().getBalance();
    }

    private static TransferRequest transfer(String key, String from, String to, String amount) {
        return TransferRequest.newBuilder()
                .setIdempotencyKey(key).setFromAccount(from).setToAccount(to).setAmount(amount).build();
    }

    private static String payKey() {
        return "PAY-" + UUID.randomUUID() + "-1-" + UUID.randomUUID();
    }

    // ---------- 测试 ----------

    /** 第 23 行：正常转账。钱从买家到商家，只动一次；回 SUCCEEDED + 流水号。 */
    @Test
    void grpcTransfer_succeeds_movesMoneyOnce() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");

        TransferResponse response = bank().transfer(transfer(payKey(), buyer, store, "100.00"));

        assertThat(response.getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(response.getTransactionId()).as("store 要把它存成 bankTxnId，以后退款用").isNotEmpty();
        assertThat(balanceOf(buyer)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("100.00");
    }

    /** 第 23 行：正常退款。全额退回，回 SUCCEEDED + 退款流水号。 */
    @Test
    void grpcRefund_succeeds() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        TransferResponse paid = bank().transfer(transfer(payKey(), buyer, store, "100.00"));

        RefundResponse refund = bank().refund(RefundRequest.newBuilder()
                .setIdempotencyKey("REFUND-" + UUID.randomUUID())
                .setOriginalTransactionId(paid.getTransactionId())
                .setReason("ORDER_CANCELLED").build());

        assertThat(refund.getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(refund.getRefundTransactionId()).isNotEmpty();
        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("0.00");
    }

    /** 第 24 行：余额不足 → 状态 OK + REJECTED，reason 写原因。不是 gRPC 错误码。 */
    @Test
    void grpcTransfer_insufficientFunds_rejected() {
        String buyer = newAccount("50.00");
        String store = newAccount("0.00");

        TransferResponse response = bank().transfer(transfer(payKey(), buyer, store, "100.00"));

        assertThat(response.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(response.getReason()).contains("Insufficient balance");
        assertThat(balanceOf(buyer)).as("钱没动").isEqualByComparingTo("50.00");
    }

    /** 第 25 行：同一个键、不同内容（键被别人先用了）→ REJECTED，绝不 SUCCEEDED。 */
    @Test
    void grpcTransfer_reusedKeyDifferentContent_rejected() {
        String attacker = newAccount("10.00");
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        String key = payKey();
        bank().transfer(transfer(key, attacker, attacker, "0.01"));

        TransferResponse real = bank().transfer(transfer(key, buyer, store, "100.00"));

        assertThat(real.getOutcome()).as("回成功的话，store 会以为收到钱了").isEqualTo(Outcome.REJECTED);
        assertThat(real.getReason()).contains("Idempotency key conflict");
        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
    }

    /** 第 26 行：同一个键发两次 → 第二次回第一次的流水号，钱只动一次。 */
    @Test
    void grpcTransfer_sameKeyTwice_sameTransactionId_chargedOnce() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        String key = payKey();

        TransferResponse first = bank().transfer(transfer(key, buyer, store, "100.00"));
        TransferResponse second = bank().transfer(transfer(key, buyer, store, "100.00"));

        assertThat(second.getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
        assertThat(balanceOf(buyer)).as("只扣一次").isEqualByComparingTo("900.00");
    }

    /**
     * 第 27 行：bank 内部出错（数据库挂了、同键并发撞唯一约束）→ gRPC 错误码 INTERNAL，**不是** OK + REJECTED。
     * 会出什么错：如果实现里 catch 所有异常、回 REJECTED，store 会把 Payment 标 FAILED → 用户 Pay Now 换新键 →
     *   如果第一次其实扣成了，就扣两次。
     */
    @Test
    void grpcTransfer_serviceThrows_errorStatus_notRejected() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        doThrow(new IllegalStateException("database is down")).when(bankService).transfer(any());

        StatusRuntimeException error = catchThrowableOfType(StatusRuntimeException.class,
                () -> bank().transfer(transfer(payKey(), buyer, store, "100.00")));

        assertThat(error).as("bank 出错要用 gRPC 错误码表示，不能回 OK").isNotNull();
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
    }

    /**
     * 第 28 行（Christy 2026-10-20 定）：金额字符串不合法 → OK + REJECTED（钱没动，bank 明确说不）。
     * 不用 INVALID_ARGUMENT：store 会把它当成"不知道"，每 10 分钟重试 + 告警，永远不会成功。
     */
    @Test
    void grpcTransfer_badAmount_rejected() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");

        TransferResponse letters = bank().transfer(transfer(payKey(), buyer, store, "abc"));
        TransferResponse empty = bank().transfer(transfer(payKey(), buyer, store, ""));

        assertThat(letters.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(empty.getOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
    }

    /**
     * 第 29 行："超时不等于失败"在真 bank 上的样子。
     * 场景：bank 处理得慢（这里用另一个事务锁住买家账户来造），store 等了 1 秒就放弃（DEADLINE_EXCEEDED）。
     *   bank 不知道 store 放弃了，锁一放开就照常扣款、提交。store 之后用同一个键重试，拿回同一个流水号，钱只扣一次。
     */
    @Test
    void clientGaveUp_bankStillCommits_retryReturnsSameResult() throws Exception {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        String key = payKey();

        // 另一个线程开事务、锁住买家账户（SELECT … FOR UPDATE），直到我们说放开
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            accountRepository.findByAccountNumberForUpdate(buyer).orElseThrow();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).as("前提：买家账户已被锁住").isTrue();

        // 动作 1：store 只等 1 秒。bank 卡在等锁，回答不了 → DEADLINE_EXCEEDED
        StatusRuntimeException timeout = catchThrowableOfType(StatusRuntimeException.class,
                () -> BankServiceGrpc.newBlockingStub(CHANNEL).withDeadlineAfter(1, TimeUnit.SECONDS)
                        .transfer(transfer(key, buyer, store, "100.00")));
        assertThat(timeout).as("store 放弃等待").isNotNull();
        assertThat(timeout.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);

        // 放开锁：bank 那边还在跑的这次扣款继续，然后提交
        release.countDown();
        holder.join(10_000);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> transactionRepository.findByTransactionRef(key).isPresent());

        // 动作 2：store 用同一个键重试
        TransferResponse retry = bank().transfer(transfer(key, buyer, store, "100.00"));

        // 断言：拿回的是 store 放弃的那一次的结果，钱只扣了一次
        assertThat(retry.getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(retry.getTransactionId())
                .isEqualTo(transactionRepository.findByTransactionRef(key).orElseThrow().getId().toString());
        assertThat(balanceOf(buyer)).as("超时那次其实扣了；重试没有再扣").isEqualByComparingTo("900.00");
    }
}
