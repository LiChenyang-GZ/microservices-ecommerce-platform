package comp5348.storeservice.adapter;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权 PR ②：失败场景表 36 号 A9（PR ① 独立审查 🟡5）。按邮箱查银行账号时，邮箱要作为**一个路径段**编码。
 *
 * 【攻击】注册时邮箱写成 x/../victim@…。BankAdapter#getAccountByOwnerEmail 把它原样拼进 URL：
 *   /api/bank/account/by-owner/x/../victim@…，bank 的 Tomcat 把 ".." 规范化掉，就成了
 *   /api/bank/account/by-owner/victim@… → 回受害人的账号 → 存成攻击者的 bankAccountNumber → 攻击者下单扣受害人的钱。
 * 【层】集成测试：真的 BankAdapter、真的 RestTemplate，WireMock 冒充 bank、记下实际收到的路径。
 * 【只断言路径】".." 的规范化是 bank 那边（Tomcat）做的，WireMock 不一定照做；所以这里证明的是"发出去的路径被改写了"，
 *   不依赖 WireMock 会不会把它规范化。
 * 【红灯为什么红】邮箱没编码，"/" 原样进了 URL，路径多出两段。
 */
class BankOwnerLookupEncodingIntegrationTest extends StoreIntegrationTest {

    private static final String PREFIX = "/api/bank/account/by-owner/";

    @Autowired BankAdapter bankAdapter;

    @Test
    void emailWithSlashes_staysOnePathSegment() {
        String trickyEmail = "x/../victim@test.local";
        FAKE_BANK.stubFor(get(urlPathMatching(PREFIX + ".*"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-WHATEVER\"}")));

        bankAdapter.getAccountByOwnerEmail(trickyEmail);

        List<LoggedRequest> requests = FAKE_BANK.findAll(getRequestedFor(urlMatching("/api/bank/.*")));
        assertThat(requests).as("store 问了 bank 一次").hasSize(1);
        String path = requests.get(0).getUrl();
        assertThat(path).startsWith(PREFIX);
        String segment = path.substring(PREFIX.length());
        assertThat(segment).as("邮箱只占一个路径段：里面不能有原样的 /").doesNotContain("/");
        assertThat(URLDecoder.decode(segment, StandardCharsets.UTF_8)).as("解码回来就是原来的邮箱")
                .isEqualTo(trickyEmail);
    }

    /** 本来就绿：普通邮箱（带 +）查得到，路径解码后就是邮箱 */
    @Test
    void ordinaryEmail_stillFound() {
        String email = "smoke+1@example.com";
        FAKE_BANK.stubFor(get(urlPathMatching(PREFIX + ".*"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-SMOKE\"}")));

        assertThat(bankAdapter.getAccountByOwnerEmail(email)).isEqualTo("ACC-SMOKE");

        String path = FAKE_BANK.findAll(getRequestedFor(urlMatching("/api/bank/.*"))).get(0).getUrl();
        assertThat(URLDecoder.decode(path.substring(PREFIX.length()), StandardCharsets.UTF_8)
                .replace(' ', '+')).isEqualTo(email);
    }
}
