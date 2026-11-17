package com.comp5348.bank;

import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.repository.BankAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * bank 留下来的 REST 接口（开户、按邮箱查账号、查余额）只给带服务凭据的调用方。
 * 失败场景表（Obsidian 36 号文件）C5、C6，回归 C7。
 *
 * 【层】集成测试：真的 bank（Spring + 真 Postgres），MockMvc 发请求，会经过 servlet 过滤器，不经过网络。
 * 【红灯为什么是红】TokenAuthenticationFilter#doFilterInternal 没带 token 就放行，BankController 自己也不查：
 *   不带凭据、带错凭据的请求都回 200，真的开了户、真的把余额给出去了。不是编译错。
 * 【约定】凭据放在 HTTP 头 X-Service-Token，和 gRPC 用同一把（配置项 bank.service-token，
 *   部署时来自环境变量 BANK_SERVICE_TOKEN）。不带或不对 → 401，bank 什么都不做。
 * 【参数化】每条"被拒绝"的测试跑两遍：token = null（不带头）和 "not-the-token"（带错）。
 */
@SpringBootTest(properties = {
        "bank.grpc.port=0",
        "bank.service-token=" + BankRestAuthIntegrationTest.SERVICE_TOKEN
})
@AutoConfigureMockMvc
class BankRestAuthIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** bank 认的那一把（只在这个测试里用的假值） */
    static final String SERVICE_TOKEN = "test-bank-service-token";
    static final String TOKEN_HEADER = "X-Service-Token";

    static {
        POSTGRES.start();
    }

    @Autowired MockMvc mvc;
    @Autowired BankAccountRepository accountRepository;

    // ---------- 小工具 ----------

    /** token 为 null 就不带头（curl 直接打 8084 就是这样） */
    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header(TOKEN_HEADER, token);
    }

    private static String newEmail() {
        return "rest-auth-" + UUID.randomUUID() + "@test.local";
    }

    private String newAccount(String balance) {
        BankAccount account = new BankAccount();
        account.setAccountNumber("ACC-R-" + UUID.randomUUID());
        account.setOwnerName("rest-auth-test");
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account).getAccountNumber();
    }

    private static String createAccountJson(String email) {
        return """
                {"ownerEmail":"%s","initialBalance":10000}""".formatted(email);
    }

    // ---------- 被拒绝的 ----------

    /** C5：不带 / 带错凭据开户 → 401，没有开出账户。现在：200，给任意邮箱开了一个带 10,000 的账户。 */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void createAccount_withoutOrWrongToken_is401_andOpensNoAccount(String token) throws Exception {
        String email = newEmail();

        mvc.perform(withToken(post("/api/bank/account"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(createAccountJson(email)))
                .andExpect(status().isUnauthorized());

        assertThat(accountRepository.findByOwnerName(email)).as("没有开出账户").isEmpty();
    }

    /**
     * C5：不带 / 带错凭据按邮箱查账号 → 401。
     * 这个 GET 其实是"查不到就开一个"（BankService#getOrCreateAccountByEmail），所以现在不只是泄露账号，还会真的开户。
     */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void getAccountByOwner_withoutOrWrongToken_is401_andOpensNoAccount(String token) throws Exception {
        String email = newEmail();

        mvc.perform(withToken(get("/api/bank/account/by-owner/" + email), token))
                .andExpect(status().isUnauthorized());

        assertThat(accountRepository.findByOwnerName(email)).as("没有开出账户").isEmpty();
    }

    /** C6：不带 / 带错凭据查余额 → 401，回复里没有余额。现在：200，任意账户的余额都能查。 */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void getBalance_withoutOrWrongToken_is401_andLeaksNoBalance(String token) throws Exception {
        String account = newAccount("4321.00");

        mvc.perform(withToken(get("/api/bank/account/" + account + "/balance"), token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("4321"))));
    }

    // ---------- 回归 ----------

    /** C7 回归：带对凭据，三个接口照常。这条现在就是绿的（bank 不看这个头），写下来防以后改坏。 */
    @Test
    void withCorrectToken_allThreeStillWork() throws Exception {
        String email = newEmail();
        String account = newAccount("4321.00");

        mvc.perform(withToken(post("/api/bank/account"), SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(createAccountJson(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        String created = accountRepository.findByOwnerName(email).orElseThrow().getAccountNumber();

        mvc.perform(withToken(get("/api/bank/account/by-owner/" + email), SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(created));

        mvc.perform(withToken(get("/api/bank/account/" + account + "/balance"), SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(4321.00));
    }
}
