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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 #17、#18（来自独立审查 07 号报告 #1、#2）：别人抢先占用了幂等键。
 *
 * 【层】集成测试：真的 bank 代码 + 真的 Postgres（Testcontainers）。直接调 BankService，不经过 HTTP。
 * 【问题】bank 见到用过的键，只返回"第一次的结果"，不看这次请求和第一次是不是同一件事。
 *   而 store 的键能猜（PAY-{paymentId}-{attempt}、REFUND-{paymentId}，paymentId 页面上就看得到），
 *   bank 的 /transfer、/refund 谁都能调。攻击者先用受害者的键做一件小事，受害者真正的请求就会拿到"已成功"。
 * 【应该】同一个键、内容不一样 → 键冲突：不能回成功，也不能动钱。
 *   （同一个键、内容一样 → 还是返回第一次的结果，那是 #2、#3、#10 的安全网，不能被这次修坏。）
 */
@SpringBootTest(properties = {
        "DB_USERNAME=unused-overridden-by-testcontainers",
        "DB_PASSWORD=unused-overridden-by-testcontainers"
})
class BankKeyConflictIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired BankService bankService;
    @Autowired BankAccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

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

    private TransferRequest transfer(String key, String from, String to, String amount) {
        TransferRequest request = new TransferRequest();
        request.setIdempotencyKey(key);
        request.setFromAccount(from);
        request.setToAccount(to);
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    private RefundRequest refund(String key, String originalTransactionId) {
        RefundRequest request = new RefundRequest();
        request.setIdempotencyKey(key);
        request.setOriginalTransactionId(originalTransactionId);
        request.setReason("Order cancelled by user");
        return request;
    }

    /**
     * #17（bank 这一半）：同一个扣款键，第二次的内容（付款人、收款人、金额）和第一次不一样 → 不能回成功。
     * 攻击剧本：攻击者猜到受害订单的键 PAY-77-2，先用它给自己转 0.01（自己转给自己，不亏）；
     * 然后 store 用同一个键发真正的 100 元扣款。
     */
    @Test
    void reusedTransferKey_withDifferentContent_isRejected() {
        String attacker = newAccount("10.00");
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        String guessedKey = "PAY-" + UUID.randomUUID() + "-2"; // 测试里用随机前缀，免得和别的测试撞；攻击里就是能猜到的那个键

        // 第 1 步：攻击者抢先用这个键，自己转给自己 0.01 —— 这一笔本身是合法的，会成功
        TransferResponse squat = bankService.transfer(transfer(guessedKey, attacker, attacker, "0.01"));
        assertThat(squat.isSuccess()).as("前提：抢先占键的那一笔本身成功").isTrue();

        // 第 2 步：store 用同一个键发真正的扣款：买家付 100 给商店
        TransferResponse real = bankService.transfer(transfer(guessedKey, buyer, store, "100.00"));

        // 断言 1（核心）：绝不能回"成功"——否则 store 会把订单标成已支付，而买家一分钱没付
        assertThat(real.isSuccess()).as("同一个键、内容不一样，bank 不能说成功").isFalse();
        // 断言 2：钱没有动（不能把第二笔当成新转账偷偷做了，也不能动攻击者的钱）
        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(attacker)).isEqualByComparingTo("10.00");
    }

    /**
     * #18（bank 这一半）：同一个退款键，第二次退的是另一笔交易 → 不能回成功，第二笔也不能被退。
     * 攻击剧本：攻击者给自己转一笔得到 T1，用受害者的退款键 REFUND-88 把 T1 退掉；
     * 之后受害者取消订单，store 用 REFUND-88 去退受害者的 T2。
     */
    @Test
    void reusedRefundKey_forAnotherTransaction_isRejected() {
        String attacker = newAccount("10.00");
        String victim = newAccount("1000.00");
        String store = newAccount("5000.00"); // 商店账户里有别人付的钱

        TransferResponse attackerPaid = bankService.transfer(
                transfer("PAY-" + UUID.randomUUID() + "-1", attacker, store, "0.01"));
        TransferResponse victimPaid = bankService.transfer(
                transfer("PAY-" + UUID.randomUUID() + "-1", victim, store, "100.00"));
        assertThat(attackerPaid.isSuccess()).isTrue();
        assertThat(victimPaid.isSuccess()).isTrue();
        String guessedRefundKey = "REFUND-" + UUID.randomUUID();

        // 第 1 步：攻击者用受害者的退款键，退掉自己那 0.01（合法，会成功）
        RefundResponse squat = bankService.refund(refund(guessedRefundKey, attackerPaid.getTransactionId()));
        assertThat(squat.isSuccess()).as("前提：抢先占键的那笔退款本身成功").isTrue();

        // 第 2 步：store 用同一个键退受害者的 100
        RefundResponse real = bankService.refund(refund(guessedRefundKey, victimPaid.getTransactionId()));

        // 断言 1（核心）：绝不能回"成功"——否则 store 记 REFUNDED、发退款邮件，钱其实还在商店账户
        assertThat(real.isSuccess()).as("同一个键、退的不是同一笔交易，bank 不能说成功").isFalse();
        // 断言 2：受害者那笔确实还没退（钱的状态和回答一致：说没退就是没退）
        assertThat(balanceOf(victim)).isEqualByComparingTo("900.00");
        long victimRefunds = transactionRepository.findAll().stream()
                .filter(t -> t.getType() == Transaction.TransactionType.REFUND)
                .filter(t -> victimPaid.getTransactionId().equals(String.valueOf(t.getOriginalTransactionId())))
                .count();
        assertThat(victimRefunds).isZero();
    }
}
