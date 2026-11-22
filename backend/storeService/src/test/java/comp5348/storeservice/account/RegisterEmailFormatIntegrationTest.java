package comp5348.storeservice.account;

import comp5348.storeservice.support.StoreHttpTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权 PR ②：失败场景表 36 号 A9 的另一半。注册时就把不像邮箱的"邮箱"挡掉，
 * 这样 x/../victim@… 这种值根本进不了库，后面拼 URL 的地方就算漏了编码也没东西可拼。
 *
 * 【层】集成测试：MockMvc + 安全链（注册是公开接口，不带 token），真的 Postgres。
 * 【红灯为什么红】AccountService#createAccount 不校验邮箱格式，什么字符串都存。
 * 【格式】本地部分只允许字母、数字和 . _ % + -，域名部分字母、数字、. -，最后一段至少两个字母。
 *   比 RFC 严，但够用：脚本的 smoke-…@example.com、带 + 的地址都能过。
 */
class RegisterEmailFormatIntegrationTest extends StoreHttpTest {

    @ParameterizedTest
    @ValueSource(strings = {"x/../victim@test.local", "a/b@test.local", "no-at-sign.test.local", "a b@test.local",
            "x@test.local/../y"})
    void notAnEmail_isRejected_andNoAccountIsCreated(String email) throws Exception {
        mvc.perform(post("/api/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email)))
                .andExpect(status().isBadRequest());

        assertThat(accountRepository.findByEmail(email)).isEmpty();
    }

    /** 本来就绿：脚本和真用户用的那种地址照样能注册 */
    @ParameterizedTest
    @ValueSource(strings = {"smoke-20261122101500@example.com", "first.last+shop@example.co.uk"})
    void ordinaryEmail_canRegister(String template) throws Exception {
        String email = template.replace("@", "-" + UUID.randomUUID().toString().substring(0, 6) + "@");

        mvc.perform(post("/api/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email)))
                .andExpect(status().isOk());

        assertThat(accountRepository.findByEmail(email)).isPresent();
    }

    private static String body(String email) {
        String username = "u-" + UUID.randomUUID().toString().substring(0, 8);
        return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}";
    }
}
