package comp5348.storeservice.adapter;

import comp5348.storeservice.dto.BankTransferRequest;
import comp5348.storeservice.support.StoreIntegrationTest;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gRPC 的回答 → BankCallResult 的映射。失败场景表（Obsidian 18 号文件）第 10、11、21 行。
 *
 * 【为什么单独一个类】这几条只有 gRPC 才有（错误码、proto3 枚举的默认值 0、金额是字符串），
 *   HTTP 版里没有对应的东西，没法先在 HTTP 上跑绿。所以它们和"换协议"一起进红灯。
 * 【层】集成测试：真 Spring 里真的 BankAdapter，连真的网络上的假 gRPC bank（FakeBankGrpc）。
 *   直接调 bankAdapter.transfer()，只测"一次调用"，不经过 OutboxProcessor 的 5 次重试，跑得快。
 * 【每条都先断言"bank 收到了请求"】否则结果是 UNKNOWN 的测试会"假绿"：store 根本没调到 gRPC bank，
 *   什么都没收到，当然也是 UNKNOWN。
 */
class BankGrpcMappingIntegrationTest extends StoreIntegrationTest {

    @Autowired BankAdapter bankAdapter;

    private BankCallResult transferOnce(String amount) {
        return bankAdapter.transfer(new BankTransferRequest(
                "PAY-test-1-" + UUID.randomUUID(), "ACC-BUYER", "STORE_ACCOUNT_001", new BigDecimal(amount)));
    }

    /**
     * 第 10 行：除了 OK，gRPC 的每一个错误码都映射成 UNKNOWN。
     * bank.proto 的约定："any other error → UNKNOWN. Never treat it as REJECTED."
     * 包括看起来"肯定没成功"的码（UNIMPLEMENTED：bank 还是旧版本；INVALID_ARGUMENT）——宁可同键多重试几次，
     * 也不能错判成失败：判成失败 → Payment FAILED → 用户 Pay Now 换新键 → 万一第一次其实扣了，就扣两次。
     */
    @ParameterizedTest(name = "{0} → UNKNOWN")
    @EnumSource(value = Status.Code.class, mode = EnumSource.Mode.EXCLUDE, names = "OK")
    void everyNonOkStatus_isUnknown(Status.Code code) {
        bankTransferFailsWith(code);

        BankCallResult result = transferOnce("100.00");

        assertThat(transferRequests()).as("store 要真的用 gRPC 调到 bank").hasSize(1);
        assertThat(result.outcome()).as(code + " 不是 bank 的回答").isEqualTo(BankCallResult.Outcome.UNKNOWN);
    }

    /**
     * 第 11 行（新行为，Christy 2026-10-20 定）：状态 OK，但 outcome 没设（OUTCOME_UNSPECIFIED = 0）→ UNKNOWN。
     * 只有 outcome 明确是 SUCCEEDED / REJECTED 才算 bank 的回答。
     * 以前的 HTTP 版遇到"2xx 但没写 success"会当成拒绝。
     */
    @Test
    void unspecifiedOutcome_isUnknown_notRejected() {
        bankTransferAnswersWithoutOutcome();

        BankCallResult result = transferOnce("100.00");

        assertThat(transferRequests()).as("store 要真的用 gRPC 调到 bank").hasSize(1);
        assertThat(result.outcome()).isEqualTo(BankCallResult.Outcome.UNKNOWN);
    }

    /**
     * 第 21 行：金额按十进制字符串发，不用科学计数法。
     * BigDecimal 的 toString() 遇到 new BigDecimal("1E+2") 会给出 "1E+2"；bank.proto 要的是 "100" 这种写法。
     * toPlainString() 才是。小数位也要原样保留（"129.90" 不变成 "129.9"）。
     */
    @Test
    void amountSentAsPlainDecimalString() {
        bankTransferSucceeds();

        transferOnce("1E+2");
        transferOnce("129.90");

        assertThat(transferRequests()).as("store 要真的用 gRPC 调到 bank").hasSize(2);
        assertThat(transferRequests().get(0).getAmount()).isEqualTo("100");
        assertThat(transferRequests().get(1).getAmount()).isEqualTo("129.90");
    }
}
