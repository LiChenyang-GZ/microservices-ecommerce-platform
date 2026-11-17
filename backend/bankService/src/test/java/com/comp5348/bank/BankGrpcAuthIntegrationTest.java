package com.comp5348.bank;

import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.repository.BankAccountRepository;
import com.comp5348.bank.repository.TransactionRepository;
import com.comp5348.contracts.bank.v1.BankServiceGrpc;
import com.comp5348.contracts.bank.v1.Outcome;
import com.comp5348.contracts.bank.v1.RefundRequest;
import com.comp5348.contracts.bank.v1.TransferRequest;
import com.comp5348.contracts.bank.v1.TransferResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * bank 的 gRPC 端口要服务凭据。失败场景表（Obsidian 36 号文件）C1、C2、C7。
 *
 * 【层】集成测试：真的 bank（Spring + 真 Postgres），测试自己当 gRPC 客户端。
 * 【红灯为什么是红】bank 的 gRPC server 没有 interceptor（GrpcServerLifecycle#start），
 *   不带凭据、带错凭据的调用都真的成功：钱真的扣了、真的退了。不是编译错，也不是连不上。
 * 【约定】凭据放在 gRPC metadata 的 x-service-token（key 必须小写）；bank 这边的配置项是 bank.service-token
 *   （部署时来自环境变量 BANK_SERVICE_TOKEN）。不对 → UNAUTHENTICATED，bank 什么都不做。
 */
@SpringBootTest
class BankGrpcAuthIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final int GRPC_PORT = freePort();
    static final ManagedChannel CHANNEL = NettyChannelBuilder.forAddress("localhost", GRPC_PORT).usePlaintext().build();

    /** bank 认的那一把（只在这个测试里用的假值） */
    static final String SERVICE_TOKEN = "test-bank-service-token";
    static final Metadata.Key<String> TOKEN_KEY = Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void bankProperties(DynamicPropertyRegistry registry) {
        registry.add("bank.grpc.port", () -> GRPC_PORT);
        registry.add("bank.service-token", () -> SERVICE_TOKEN);
    }

    @AfterAll
    static void closeChannel() {
        CHANNEL.shutdownNow();
    }

    @Autowired BankAccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

    // ---------- 小工具 ----------

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 不带任何凭据的客户端（grpcurl 直接连 9090 就是这样） */
    private static BankServiceGrpc.BankServiceBlockingStub noToken() {
        return BankServiceGrpc.newBlockingStub(CHANNEL).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    /** 带指定凭据的客户端 */
    private static BankServiceGrpc.BankServiceBlockingStub withToken(String token) {
        Metadata headers = new Metadata();
        headers.put(TOKEN_KEY, token);
        return noToken().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private String newAccount(String balance) {
        BankAccount account = new BankAccount();
        account.setAccountNumber("ACC-A-" + UUID.randomUUID());
        account.setOwnerName("grpc-auth-test");
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

    private static RefundRequest refundOf(String key, String transactionId) {
        return RefundRequest.newBuilder()
                .setIdempotencyKey(key).setOriginalTransactionId(transactionId).setReason("ORDER_CANCELLED").build();
    }

    // ---------- 测试 ----------

    /** C1：不带凭据调 Transfer → UNAUTHENTICATED，余额不变，没有新流水。 */
    @Test
    void transfer_withoutToken_isUnauthenticated_andMovesNoMoney() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        String key = payKey();

        StatusRuntimeException error = catchThrowableOfType(StatusRuntimeException.class,
                () -> noToken().transfer(transfer(key, buyer, store, "100.00")));

        assertThat(error).as("不带凭据的 Transfer 必须被拒绝").isNotNull();
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("0.00");
        assertThat(transactionRepository.findByTransactionRef(key)).isEmpty();
    }

    /** C2：凭据不对 → 和不带一样。 */
    @Test
    void transfer_withWrongToken_isUnauthenticated_andMovesNoMoney() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        String key = payKey();

        StatusRuntimeException error = catchThrowableOfType(StatusRuntimeException.class,
                () -> withToken("not-the-token").transfer(transfer(key, buyer, store, "100.00")));

        assertThat(error).as("凭据不对的 Transfer 必须被拒绝").isNotNull();
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
        assertThat(transactionRepository.findByTransactionRef(key)).isEmpty();
    }

    /** C1（退款那一半）：先正常付一笔，再不带凭据 / 带错凭据调 Refund → UNAUTHENTICATED，钱没退回去。 */
    @Test
    void refund_withoutOrWrongToken_isUnauthenticated_andRefundsNothing() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        TransferResponse paid = withToken(SERVICE_TOKEN).transfer(transfer(payKey(), buyer, store, "100.00"));
        assertThat(paid.getOutcome()).as("前提：带对凭据的付款成功").isEqualTo(Outcome.SUCCEEDED);
        String noTokenKey = "REFUND-" + UUID.randomUUID();
        String wrongTokenKey = "REFUND-" + UUID.randomUUID();

        StatusRuntimeException noTokenError = catchThrowableOfType(StatusRuntimeException.class,
                () -> noToken().refund(refundOf(noTokenKey, paid.getTransactionId())));
        StatusRuntimeException wrongTokenError = catchThrowableOfType(StatusRuntimeException.class,
                () -> withToken("not-the-token").refund(refundOf(wrongTokenKey, paid.getTransactionId())));

        assertThat(noTokenError).as("不带凭据的 Refund 必须被拒绝").isNotNull();
        assertThat(noTokenError.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(wrongTokenError).as("凭据不对的 Refund 必须被拒绝").isNotNull();
        assertThat(wrongTokenError.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(balanceOf(buyer)).as("钱没退回买家").isEqualByComparingTo("900.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByTransactionRef(noTokenKey)).isEmpty();
        assertThat(transactionRepository.findByTransactionRef(wrongTokenKey)).isEmpty();
    }

    /** C7 回归：带对凭据，付款和退款照常。这条现在就是绿的（bank 不看 metadata），写下来防以后改坏。 */
    @Test
    void transferAndRefund_withCorrectToken_stillWork() {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");

        TransferResponse paid = withToken(SERVICE_TOKEN).transfer(transfer(payKey(), buyer, store, "100.00"));
        var refund = withToken(SERVICE_TOKEN).refund(refundOf("REFUND-" + UUID.randomUUID(), paid.getTransactionId()));

        assertThat(paid.getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(refund.getOutcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("0.00");
    }
}
