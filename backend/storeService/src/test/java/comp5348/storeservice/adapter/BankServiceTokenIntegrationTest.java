package comp5348.storeservice.adapter;

import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankTransferRequest;
import comp5348.storeservice.support.StoreIntegrationTest;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * store 调 bank 时带上服务凭据；bank 说"凭据不对"时 store 怎么办。
 * 失败场景表（Obsidian 36 号文件）C3，以及 C7 的 store 这一半（bank 开始检查以后，store 不带就全被拒）。
 *
 * 【层】集成测试：真 Spring 里真的 BankAdapter，连假 bank（gRPC 的 FakeBankGrpc + HTTP 的 WireMock）。
 *   直接调 bankAdapter 的方法，只测"一次调用"。假 bank 不检查凭据，只把收到的记下来给测试看。
 * 【红灯为什么是红】不是编译错，是 store 少做了事：
 *   - BankAdapter 的 channel 没有 ClientInterceptor，REST 调用也不加头 → 假 bank 收到的凭据是空的。
 *   - BankAdapter#unknown 对所有 gRPC 错误码一视同仁 → UNAUTHENTICATED 时没有单独的 ALERT。
 * 【约定】gRPC：metadata 的 x-service-token；HTTP：X-Service-Token 头；store 的配置项 bank.service-token。
 *   告警行：ALERT bank_auth_failed key={幂等键}（和现有的 ALERT payment_unknown 一个风格，运维按 "ALERT " 搜日志）。
 */
@ExtendWith(OutputCaptureExtension.class) // 把控制台输出（日志）抓下来，检查有没有打出告警
class BankServiceTokenIntegrationTest extends StoreIntegrationTest {

    @Autowired BankAdapter bankAdapter;

    private static BankTransferRequest transferRequest() {
        return new BankTransferRequest(
                "PAY-test-1-" + UUID.randomUUID(), "ACC-BUYER", "STORE_ACCOUNT_001", new BigDecimal("100.00"));
    }

    private static BankRefundRequest refundRequest() {
        return new BankRefundRequest("REFUND-test-" + UUID.randomUUID(), "9001", "ORDER_CANCELLED");
    }

    // ---------- store 带没带凭据 ----------

    /** gRPC 扣款、退款：每个请求的 metadata 里都有 x-service-token。现在：没带（null）。 */
    @Test
    void transferAndRefund_carryTheServiceToken() {
        bankTransferSucceeds();
        bankRefundSucceeds();

        bankAdapter.transfer(transferRequest());
        bankAdapter.refund(refundRequest());

        assertThat(transferRequests()).as("store 要真的调到 bank").hasSize(1);
        assertThat(refundRequests()).as("store 要真的调到 bank").hasSize(1);
        assertThat(bankServiceTokens()).containsExactly(BANK_SERVICE_TOKEN, BANK_SERVICE_TOKEN);
    }

    /** REST 开户：带 X-Service-Token 头。现在：没带。 */
    @Test
    void createCustomerAccount_carriesTheServiceToken() {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/account"))
                .willReturn(okJson("{\"success\":true,\"accountNumber\":\"ACC-NEW\"}")));

        String account = bankAdapter.createCustomerAccount("new-user@test.local", new BigDecimal("10000"));

        assertThat(account).as("前提：store 调到了假 bank 并读到账号").isEqualTo("ACC-NEW");
        FAKE_BANK.verify(postRequestedFor(urlEqualTo("/api/bank/account"))
                .withHeader(SERVICE_TOKEN_HEADER, equalTo(BANK_SERVICE_TOKEN)));
    }

    /** REST 按邮箱查账号：带 X-Service-Token 头。现在：没带。 */
    @Test
    void getAccountByOwnerEmail_carriesTheServiceToken() {
        String path = "/api/bank/account/by-owner/owner@test.local";
        FAKE_BANK.stubFor(get(urlEqualTo(path))
                .willReturn(okJson("{\"success\":true,\"accountNumber\":\"ACC-OWNER\"}")));

        String account = bankAdapter.getAccountByOwnerEmail("owner@test.local");

        assertThat(account).as("前提：store 调到了假 bank 并读到账号").isEqualTo("ACC-OWNER");
        FAKE_BANK.verify(getRequestedFor(urlEqualTo(path))
                .withHeader(SERVICE_TOKEN_HEADER, equalTo(BANK_SERVICE_TOKEN)));
    }

    // ---------- bank 说凭据不对 ----------

    /**
     * C3：bank 回 UNAUTHENTICATED（密钥配错了）。
     * - 结果还是 UNKNOWN，不是 REJECTED：REJECTED 会让 Payment 变 FAILED、用户 Pay Now 换新键，
     *   而配错密钥是我们的错，不是用户的错。这一半本来就绿（BankGrpcMappingIntegrationTest 对每个错误码都测了）。
     * - 另外打一行 ALERT bank_auth_failed：否则密钥配错时日志里只有一堆和超时长得一样的重试。这一半是红的。
     */
    @Test
    void bankSaysUnauthenticated_transferIsUnknown_andRaisesAlert(CapturedOutput output) {
        bankTransferFailsWith(Status.Code.UNAUTHENTICATED);
        BankTransferRequest request = transferRequest();

        BankCallResult result = bankAdapter.transfer(request);

        assertThat(transferRequests()).as("store 要真的调到 bank").hasSize(1);
        assertThat(result.outcome()).isEqualTo(BankCallResult.Outcome.UNKNOWN);
        assertThat(output.getAll())
                .containsPattern("ALERT bank_auth_failed.*key=" + Pattern.quote(request.getIdempotencyKey()));
    }

    /** C3，退款那一半：同样 UNKNOWN + ALERT。 */
    @Test
    void bankSaysUnauthenticated_refundIsUnknown_andRaisesAlert(CapturedOutput output) {
        bankRefundFailsWith(Status.Code.UNAUTHENTICATED);
        BankRefundRequest request = refundRequest();

        BankCallResult result = bankAdapter.refund(request);

        assertThat(refundRequests()).as("store 要真的调到 bank").hasSize(1);
        assertThat(result.outcome()).isEqualTo(BankCallResult.Outcome.UNKNOWN);
        assertThat(output.getAll())
                .containsPattern("ALERT bank_auth_failed.*key=" + Pattern.quote(request.getIdempotencyKey()));
    }

    /** 反过来：别的错误码（这里用 UNAVAILABLE，bank 重启时就是它）不打这个告警，免得一重启就告警。本来就绿。 */
    @Test
    void otherErrors_doNotRaiseTheAuthAlert(CapturedOutput output) {
        bankTransferFailsWith(Status.Code.UNAVAILABLE);

        bankAdapter.transfer(transferRequest());

        assertThat(transferRequests()).as("store 要真的调到 bank").hasSize(1);
        assertThat(output.getAll()).doesNotContain("ALERT bank_auth_failed");
    }
}
