package com.comp5348.bank;

import com.comp5348.bank.dto.TransferRequest;
import com.comp5348.bank.dto.TransferResponse;
import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.repository.BankAccountRepository;
import com.comp5348.bank.repository.TransactionRepository;
import com.comp5348.bank.service.BankService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Christy 的决定 3（2026-11-17）：没有调用方的危险接口删掉。失败场景表（Obsidian 36 号文件）C4。
 *   - POST /api/bank/transfer、/refund：钱只走 gRPC（ADR-10），store 早就不调这两个 REST 接口了。
 *     留着就是第二个扣款 / 退款入口。
 *   - GET /api/bank/account/me：给登录用户的，前端和 store 都不调。
 *
 * 【层】集成测试：真的 bank（Spring + 真 Postgres），MockMvc 发请求，不经过网络。
 * 【为什么每个请求都带对的服务凭据】绿灯以后 /api/bank/* 都要凭据，不带的话会先被挡成 401，
 *   那就分不清"接口没了"和"接口还在、只是没凭据"。带对凭据还是 404，才证明接口真的删了。
 * 【红灯为什么是红】transfer、refund 回 200，钱真的动了、真的写了流水。
 *   account/me 回的是 401（BankController#getMyAccount 自己查了 userEmail）：接口还在，但这一条不是"请求成功了"。
 */
@SpringBootTest(properties = {
        "bank.grpc.port=0",
        "bank.service-token=" + OldBankRestEndpointsRemovedTest.SERVICE_TOKEN
})
@AutoConfigureMockMvc
class OldBankRestEndpointsRemovedTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String SERVICE_TOKEN = "test-bank-service-token";
    static final String TOKEN_HEADER = "X-Service-Token";

    static {
        POSTGRES.start();
    }

    @Autowired MockMvc mvc;
    @Autowired BankService bankService;
    @Autowired BankAccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

    private String newAccount(String balance) {
        BankAccount account = new BankAccount();
        account.setAccountNumber("ACC-O-" + UUID.randomUUID());
        account.setOwnerName("old-rest-test");
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account).getAccountNumber();
    }

    private BigDecimal balanceOf(String account) {
        return accountRepository.findByAccountNumber(account).orElseThrow().getBalance();
    }

    @Test
    void transferEndpointIsGone_andMovesNoMoney() throws Exception {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        String key = "PAY-" + UUID.randomUUID();

        mvc.perform(post("/api/bank/transfer").header(TOKEN_HEADER, SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"%s","fromAccount":"%s","toAccount":"%s","amount":100.00}"""
                                .formatted(key, buyer, store)))
                .andExpect(status().isNotFound());

        assertThat(balanceOf(buyer)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("0.00");
        assertThat(transactionRepository.findByTransactionRef(key)).isEmpty();
    }

    @Test
    void refundEndpointIsGone_andRefundsNothing() throws Exception {
        String buyer = newAccount("1000.00");
        String store = newAccount("0.00");
        // 前提：先正常付一笔（直接调 BankService，gRPC 走的也是这个方法）
        TransferResponse paid = bankService.transfer(
                new TransferRequest("PAY-" + UUID.randomUUID(), buyer, store, new BigDecimal("100.00")));
        assertThat(paid.isSuccess()).as("前提：付款成功").isTrue();
        String key = "REFUND-" + UUID.randomUUID();

        mvc.perform(post("/api/bank/refund").header(TOKEN_HEADER, SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"%s","originalTransactionId":"%s","reason":"ORDER_CANCELLED"}"""
                                .formatted(key, paid.getTransactionId())))
                .andExpect(status().isNotFound());

        assertThat(balanceOf(buyer)).as("钱没退回买家").isEqualByComparingTo("900.00");
        assertThat(balanceOf(store)).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.findByTransactionRef(key)).isEmpty();
    }

    @Test
    void accountMeEndpointIsGone() throws Exception {
        mvc.perform(get("/api/bank/account/me").header(TOKEN_HEADER, SERVICE_TOKEN))
                .andExpect(status().isNotFound());
    }
}
