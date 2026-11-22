package comp5348.storeservice.adapter;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 鉴权 PR ②：失败场景表 36 号 A9（PR ① 独立审查 🟡5，PR ② 独立审查 🟡3、🟡6）。
 * 按邮箱查银行账号时，邮箱要作为**一个路径段**、严格编码。
 *
 * 【攻击】注册时邮箱写成 x/../victim@…，或者（PR ② 之前注册的存量账号）victim@…;x。
 *   BankAdapter#getAccountByOwnerEmail 把它拼进 URL，bank 的 Tomcat 把 ".." 规范化掉、把 ";x" 当路径参数去掉，
 *   @PathVariable 就成了 victim@… → 回受害人的账号 → 存成攻击者的 bankAccountNumber → 攻击者下单扣受害人的钱。
 * 【层】集成测试：真的 BankAdapter、真的 RestTemplate，WireMock 冒充 bank、记下实际收到的路径。
 * 【只断言路径】规范化、去路径参数是 bank 那边（Tomcat / Spring）做的，WireMock 不一定照做；
 *   所以这里证明的是"发出去的路径段里没有会被 bank 另作解释的字符，按路径规则解码回来就是原邮箱"。
 * 【怎么解码】用 UriUtils#decode（路径规则：+ 就是 +）。不能用 URLDecoder（表单规则：+ 变空格），
 *   bank 那边的 Spring 按路径规则解码（44 号 🟡6：原来那条测试用 URLDecoder，路径里是 + 还是空格它都能过）。
 */
class BankOwnerLookupEncodingIntegrationTest extends StoreIntegrationTest {

    private static final String PREFIX = "/api/bank/account/by-owner/";

    @Autowired BankAdapter bankAdapter;

    /**
     * 红（PR ② 第一次红灯）：x/../victim 里的 / 原样进了 URL。
     * 红（审查后补的）：; 原样进了 URL（模板模式的 pathSegment 不编码 sub-delims）
     */
    @ParameterizedTest
    @ValueSource(strings = {"x/../victim@test.local", "victim@test.local;x", "victim@test.local;jsessionid=1"})
    void trickyEmail_staysOneSegment_withNothingBankWouldReinterpret(String trickyEmail) {
        FAKE_BANK.stubFor(get(urlPathMatching(PREFIX + ".*"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-WHATEVER\"}")));

        bankAdapter.getAccountByOwnerEmail(trickyEmail);

        String segment = onlySegmentBankReceived();
        assertThat(segment).as("邮箱只占一个路径段：里面不能有原样的 /").doesNotContain("/");
        assertThat(segment).as("不能有原样的 ;（bank 会把它后面当路径参数去掉）").doesNotContain(";");
        assertThat(UriUtils.decode(segment, StandardCharsets.UTF_8)).as("按路径规则解码回来就是原来的邮箱")
                .isEqualTo(trickyEmail);
    }

    /** 红（审查后补的）：{b} 被当成没展开的 URI 变量，toUri() 在 try 外面抛 IllegalStateException */
    @Test
    void braces_doNotBlowUp() {
        FAKE_BANK.stubFor(get(urlPathMatching(PREFIX + ".*"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-BRACES\"}")));

        assertThatCode(() -> bankAdapter.getAccountByOwnerEmail("a{b}@test.local")).doesNotThrowAnyException();
    }

    /** 本来就绿：普通邮箱（带 +）查得到，按路径规则解码后就是原邮箱，+ 没变成空格 */
    @Test
    void ordinaryEmail_stillFound() {
        String email = "smoke+1@example.com";
        FAKE_BANK.stubFor(get(urlPathMatching(PREFIX + ".*"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-SMOKE\"}")));

        assertThat(bankAdapter.getAccountByOwnerEmail(email)).isEqualTo("ACC-SMOKE");
        assertThat(UriUtils.decode(onlySegmentBankReceived(), StandardCharsets.UTF_8)).isEqualTo(email);
    }

    private String onlySegmentBankReceived() {
        List<LoggedRequest> requests = FAKE_BANK.findAll(getRequestedFor(urlMatching("/api/bank/.*")));
        assertThat(requests).as("store 问了 bank 一次").hasSize(1);
        String path = requests.get(0).getUrl();
        assertThat(path).startsWith(PREFIX);
        return path.substring(PREFIX.length());
    }
}
