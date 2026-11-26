package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.support.StoreHttpTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户名规则（Obsidian 78 号，U1–U6；75 号 🟡1）。
 *
 * 以前：注册对用户名什么都不查。用户名能带 @（长得像别人的邮箱：登录先按邮箱找，会找到别人；47 号 🟠2 抢先占 ADMIN_EMAIL
 *   当用户名也是这条路），能带空格、长得一样的 Unicode、几百个字符。
 * 改完（关口 0 方案 B）：先 trim，再要求 ^[A-Za-z0-9._-]{3,32}$，否则 400，在查邮箱、开银行账户之前。只管注册。
 *
 * 【层】集成测试：MockMvc + 安全链（注册是公开接口），真的 Postgres；假 bank 是 WireMock。
 * 【红灯为什么红】createAccount 不查用户名：不合规的照样 200、建了账号、调了 bank；" bob " 原样存。不是编译错。
 */
class UsernameRulesIntegrationTest extends StoreHttpTest {

    private static final String RULE = "Username must be 3-32 characters: letters, digits, dot, underscore or hyphen";

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions register(String username, String email) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"Passw0rd-123\"}";
        return mvc.perform(post("/api/user").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void bankOpensAccounts() {
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo("/api/bank/account"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-U-" + tag() + "\"}")));
    }

    private int accountsWithEmail(String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM account WHERE email = ?", Integer.class, email);
        return n == null ? 0 : n;
    }

    /** 不合规 → 400 + 规则，不建账号、不开银行账户 */
    private void assertRefused(String username) throws Exception {
        String email = "u-" + tag() + "@test.local";
        bankOpensAccounts();

        register(username, email)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(RULE));

        assertThat(accountsWithEmail(email)).as("没建账号").isZero();
        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo("/api/bank/account")));
    }

    /** U1：用户名带 @。现在：200。 */
    @Test
    void usernameWithAnAt_isRefused() throws Exception {
        assertRefused("Bob@X.com");
    }

    /** U2：用户名 = 别人的邮箱换个大小写（75 🟡1 的起点：登录时会先按邮箱找到别人）。现在：200。 */
    @Test
    void usernameThatIsSomeoneElsesEmail_isRefused() throws Exception {
        String other = "other-" + tag() + "@test.local";
        Account account = new Account();
        account.setEmail(other);
        account.setUsername("other-" + tag());
        account.setPassword("x");
        accountRepository.save(account);

        assertRefused(other.toUpperCase());
    }

    /**
     * U3：空格、西里尔字母 а（U+0430，冒充 admin）、制表符、太短、太长。现在：都是 200。
     * （JSON 里的 \t 是制表符；西里尔字母直接写进 JSON 字符串。）
     */
    @ParameterizedTest
    @ValueSource(strings = {"bob smith", "аdmin", "bob\\tsmith", "ab", "a23456789012345678901234567890123"})
    void usernameOutsideTheRules_isRefused(String username) throws Exception {
        assertRefused(username);
    }

    /** U4：前后空白去掉后注册，存的是 bob-xxxx。现在：原样存 "  bob-xxxx "。 */
    @Test
    void surroundingSpaces_areTrimmed() throws Exception {
        String username = "bob-" + tag();
        String email = "trim-" + tag() + "@test.local";
        bankOpensAccounts();

        register("  " + username + " ", email).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT username FROM account WHERE email = ?", String.class, email))
                .isEqualTo(username);
    }

    /** U4：去掉空白后和已有用户名一样 → "Username already exists!"。现在：" bob-xxxx " 算另一个名字，200。 */
    @Test
    void surroundingSpaces_doNotMakeANewUsername() throws Exception {
        String username = "taken-" + tag();
        bankOpensAccounts();
        register(username, "first-" + tag() + "@test.local").andExpect(status().isOk());

        bankOpensAccounts(); // 新号码：红灯时第二次注册会成功，同一个号码会撞银行账号的唯一约束，红错地方
        register(" " + username + " ", "second-" + tag() + "@test.local")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username already exists!"));
    }

    /** U5：规则内的用户名照样能注册（回归，现在就该绿） */
    @Test
    void usernameWithinTheRules_isAccepted() throws Exception {
        String exactlyThree = tag().substring(0, 2) + "_";            // 下边界 3 个
        String exactlyThirtyTwo = "B.b-b_" + "b".repeat(18) + tag();  // 上边界 32 个
        for (String username : new String[]{"new_user.1-" + tag(), "ABC" + tag(), exactlyThree, exactlyThirtyTwo}) {
            bankOpensAccounts(); // 每个账号一个新的银行号码（号码列是唯一的）
            register(username, "ok-" + tag() + "@test.local")
                    .andExpect(status().isOk());
        }
    }

    /**
     * U6：用户名不合规、邮箱已被注册但没激活 → 只说用户名的规则，不说"已注册未验证"（用户名错的请求不该打听到邮箱的状态）。
     * 现在：回 emailNotVerified。
     */
    @Test
    void badUsername_isReportedBeforeAnythingAboutTheEmail() throws Exception {
        String email = "pending-" + tag() + "@test.local";
        Account account = new Account();
        account.setEmail(email);
        account.setUsername("pending-" + tag());
        account.setPassword("x");
        account.setActive(false);
        account.setEmailVerified(false);
        accountRepository.save(account);

        register("Bad@Name", email)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(RULE))
                .andExpect(jsonPath("$.emailNotVerified").value(false));
    }
}
