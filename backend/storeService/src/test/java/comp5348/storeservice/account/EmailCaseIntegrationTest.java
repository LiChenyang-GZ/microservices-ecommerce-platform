package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.support.StoreHttpTest;
import comp5348.storeservice.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱大小写归一（Obsidian 74 号，E1–E5、E9；58 号决定 3）。
 *
 * 以前：store 拿用户输的邮箱原样查、原样存。Postgres 的唯一约束区分大小写，所以 Victim@x.com 和 victim@x.com
 *   是两个账号、两个银行账户；用 victim@x.com 注册的人，手机输入法把首字母变大写以后就登录不了、找回不了。
 * 改完（关口 0 方案 A）：EmailAddress#normalize（去空格、转小写）。Account#setEmail 管所有写，
 *   AccountRepository#findByEmail 管所有读，AccountService 传给 email / bank 的也是归一后的。
 *
 * 【层】集成测试：MockMvc + 安全链，真的 Postgres、Redis；假 email / bank 是 WireMock（同一个 FAKE_BANK）。
 * 【红灯为什么红】EmailAddress#normalize 是原样返回的桩，Account、AccountRepository、AccountService 都没调它：
 *   找不到账号、建出第二个账号、存进去的是大写。不是编译错。
 * 【约定】email 的四个接口回 200 {"success":true} = 发出去了 / 码对；请求体里的邮箱字段叫 email。
 *   bank 开户 POST /api/bank/account，请求体里叫 ownerEmail。
 */
class EmailCaseIntegrationTest extends StoreHttpTest {

    private static final String PASSWORD = "Passw0rd-123";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 已激活、有银行账户的账号；邮箱照常是小写 */
    private Account activeAccount(String email) {
        Account account = new Account();
        account.setEmail(email);
        account.setUsername("case-" + tag());
        account.setPassword(ENCODER.encode(PASSWORD));
        account.setEmailVerified(true);
        account.setActive(true);
        account.setBankAccountNumber("ACC-" + tag());
        return accountRepository.save(account);
    }

    private ResultActions json(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions register(String username, String email) throws Exception {
        return json("/api/user",
                "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private ResultActions login(String email, String password) throws Exception {
        return json("/api/user/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private int accountsFor(String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM account WHERE lower(email) = lower(?)", Integer.class, email.trim());
        return n == null ? 0 : n;
    }

    private String storedEmailOf(String username) {
        return jdbc.queryForObject("SELECT email FROM account WHERE username = ?", String.class, username);
    }

    private static void bankOpensAccounts() {
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo("/api/bank/account"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-CASE-" + tag() + "\"}")));
    }

    private static void emailAnswersYes(String path) {
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(path)).willReturn(okJson("{\"success\":true,\"message\":\"ok\"}")));
    }

    /** email 被问到的那一次，带的是归一后的邮箱 */
    private static void emailWasAskedWith(String path, String email) {
        FAKE_BANK.verify(1, postRequestedFor(urlEqualTo(path)).withRequestBody(matchingJsonPath("$.email", equalTo(email))));
    }

    // ---------- E1：换个大小写不能再注册一个 ----------

    /** E1：victim@ 已经激活，有人注册 Victim@ → "Email already exists!"，不建第二个账号、不开第二个银行账户。现在：200，两个账号。 */
    @Test
    void register_takenEmailInOtherCase_saysItExists_andOpensNothing() throws Exception {
        String email = "taken-" + tag() + "@test.local";
        activeAccount(email);
        bankOpensAccounts();

        register("second-" + tag(), "Taken" + email.substring(5).toUpperCase())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists!"));

        assertThat(accountsFor(email)).as("还是只有一个账号").isEqualTo(1);
        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo("/api/bank/account")));
    }

    // ---------- E2：手机把首字母变大写 ----------

    /** E2：用小写注册的人，登录时输 Login-...@Test.Local → 200，回答和 token 里是小写的那个邮箱。现在：401。 */
    @Test
    void login_withCapitalisedEmail_works() throws Exception {
        String email = "login-" + tag() + "@test.local";
        activeAccount(email);

        login("L" + email.substring(1, 10).toUpperCase() + email.substring(10), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.token").value(not(emptyOrNullString())));
    }

    // ---------- E3：注册时就是大写 ----------

    /**
     * E3：注册输 Signup-...@Test.Local → 存成小写，银行账户按小写开；要码、激活时再换个大小写也行，问 email 时带的都是小写
     * （码在 email 那边按邮箱存，大小写不一样就对不上）。现在：存的是大写，要码 / 激活时找不到账号。
     */
    @Test
    void register_inCapitals_storesOneSpelling_andCodeAndActivationAcceptAnyCase() throws Exception {
        String lower = "signup-" + tag() + "@test.local";
        String username = "signup-" + tag();
        bankOpensAccounts();
        emailAnswersYes("/api/email/send-verification");
        emailAnswersYes("/api/email/verify-code");

        register(username, "Signup" + lower.substring(6, 15).toUpperCase() + "@Test.Local").andExpect(status().isOk());

        assertThat(storedEmailOf(username)).as("存进库里的是一种写法").isEqualTo(lower);
        FAKE_BANK.verify(1, postRequestedFor(urlEqualTo("/api/bank/account"))
                .withRequestBody(matchingJsonPath("$.ownerEmail", equalTo(lower))));

        json("/api/user/send-verification", "{\"email\":\"" + lower.toUpperCase() + "\"}").andExpect(status().isOk());
        emailWasAskedWith("/api/email/send-verification", lower);

        json("/api/user/activate", "{\"email\":\"" + lower.toUpperCase() + "\",\"code\":\"0123456789\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Account activated successfully"));
        emailWasAskedWith("/api/email/verify-code", lower);
        assertThat(accountRepository.findByEmail(lower).orElseThrow().getActive()).isTrue();
    }

    // ---------- E4：找回、重置 ----------

    /** E4：找回和重置时大小写和注册时不同 → 照样发码、照样重置；问 email 带的是小写。现在：400 "Email does not exist"。 */
    @Test
    void forgotAndResetPassword_inOtherCase_work() throws Exception {
        String email = "reset-" + tag() + "@test.local";
        activeAccount(email);
        emailAnswersYes("/api/email/send-password-reset");
        emailAnswersYes("/api/email/verify-password-reset");

        json("/api/user/forgot-password", "{\"email\":\"" + email.toUpperCase() + "\"}").andExpect(status().isOk());
        emailWasAskedWith("/api/email/send-password-reset", email);

        json("/api/user/reset-password",
                "{\"email\":\"Reset" + email.substring(5) + "\",\"code\":\"0123456789\",\"newPassword\":\"New-Passw0rd\"}")
                .andExpect(status().isOk());
        emailWasAskedWith("/api/email/verify-password-reset", email);

        login(email, "New-Passw0rd").andExpect(status().isOk());
    }

    // ---------- E5：前后的空格 ----------

    /** E5：注册时邮箱前后带空格 → 去掉空格后注册成功；登录时带空格也行。现在：注册 400（格式校验不收空格）。 */
    @Test
    void surroundingSpaces_areIgnored_atRegistrationAndLogin() throws Exception {
        String email = "spaces-" + tag() + "@test.local";
        String username = "spaces-" + tag();
        bankOpensAccounts();

        register(username, "  " + email + " ").andExpect(status().isOk());
        assertThat(storedEmailOf(username)).isEqualTo(email);

        Account account = accountRepository.findById(
                jdbc.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, username)).orElseThrow();
        account.setActive(true);
        account.setEmailVerified(true);
        accountRepository.save(account);

        login(" " + email + "  ", PASSWORD).andExpect(status().isOk());
    }

    // ---------- 审查 75 号 🟡2：Pay Now 按库里的写法查银行账户 ----------

    @Autowired private JwtUtil jwtUtil;

    /**
     * 有大小写混杂老数据的环境迁移以后（ADR-31），用户手里还拿着迁移前签的令牌，里面是 BUYER-...@TEST.LOCAL；
     * 账号上没存银行号码。Pay Now 要按**库里的**写法（小写）去 bank 查户主，不能按令牌里的。
     * 现在：把令牌里的大写发给 bank，bank 查不到（真 bank 这时会新开一个余额 0 的户），store 回 400。
     */
    @Test
    void payNow_withATokenInAnOldSpelling_looksUpTheBankAccountByTheStoredEmail() throws Exception {
        PlacedOrder order = placeOrder();
        Account buyer = accountRepository.findByEmail(order.buyerEmail()).orElseThrow();
        jdbc.update("UPDATE account SET bank_account_number = NULL WHERE id = ?", buyer.getId());
        String lowerPath = "/api/bank/account/by-owner/" + order.buyerEmail().replace("@", "%40");
        FAKE_BANK.stubFor(get(urlEqualTo(lowerPath)).willReturn(okJson("{\"accountNumber\":\"ACC-FOUND-" + tag() + "\"}")));
        String oldToken = "Bearer " + jwtUtil.generateToken(buyer.getId(), order.buyerEmail().toUpperCase(), buyer.getRole());

        mvc.perform(post("/api/store/orders/{id}/retry-payment", order.orderId()).header("Authorization", oldToken))
                .andExpect(status().isOk());

        FAKE_BANK.verify(1, getRequestedFor(urlEqualTo(lowerPath)));
        assertThat(accountRepository.findById(buyer.getId()).orElseThrow().getBankAccountNumber()).startsWith("ACC-FOUND-");
    }

    // ---------- E9：两个卡口 ----------

    /** E9 读的卡口：不经过 AccountService，直接用别的大小写查仓库，也找到那个账号。现在：找不到。 */
    @Test
    void repository_findByEmail_inOtherCase_findsTheAccount() {
        String email = "repo-" + tag() + "@test.local";
        Account account = activeAccount(email);

        assertThat(accountRepository.findByEmail("  " + email.toUpperCase()))
                .map(Account::getId).contains(account.getId());
    }

    /** E9 写的卡口：不管哪条路径存账号，库里都是归一后的写法。现在：原样存。 */
    @Test
    void savingAnAccount_storesTheEmailInOneSpelling() {
        String t = tag();
        Account account = activeAccount(" Mixed-" + t.toUpperCase() + "@Test.Local");

        assertThat(jdbc.queryForObject("SELECT email FROM account WHERE id = ?", String.class, account.getId()))
                .isEqualTo("mixed-" + t + "@test.local");
    }
}
