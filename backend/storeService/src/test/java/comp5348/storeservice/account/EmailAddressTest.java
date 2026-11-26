package comp5348.storeservice.account;

import comp5348.storeservice.utils.EmailAddress;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E8（Obsidian 74 号）：一个信箱只有一种写法。store 存邮箱、查邮箱之前都先过 EmailAddress#normalize。
 *
 * 【层】单元测试，不起 Spring。
 * 【红灯为什么红】红灯提交里 normalize 是原样返回的桩。不是编译错。
 */
class EmailAddressTest {

    /** 手机输入法把首字母变大写、复制时带上空格：都算同一个信箱 */
    @Test
    void upperCaseAndSurroundingSpaces_becomeOneSpelling() {
        assertThat(EmailAddress.normalize("  Victim@Example.COM \t")).isEqualTo("victim@example.com");
    }

    @Test
    void alreadyNormal_staysTheSame() {
        assertThat(EmailAddress.normalize("victim@example.com")).isEqualTo("victim@example.com");
    }

    /** 请求体里没给邮箱：原样交给后面的校验去拒，这里不抛异常 */
    @Test
    void null_staysNull() {
        assertThat(EmailAddress.normalize(null)).isNull();
    }

    /**
     * 服务器默认语言是土耳其语时，"I".toLowerCase() 是没有点的 "ı"：TIM@X.COM 会变成 tım@x.com，
     * 和用户注册时的 tim@x.com 对不上。归一要和语言无关（Locale.ROOT，和 AuthRateLimits 一样）。
     */
    @Test
    void doesNotDependOnTheServersLanguage() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(EmailAddress.normalize("TIM@EXAMPLE.COM")).isEqualTo("tim@example.com");
        } finally {
            Locale.setDefault(before);
        }
    }
}
