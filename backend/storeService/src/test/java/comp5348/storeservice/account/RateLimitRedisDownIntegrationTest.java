package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.support.StoreHttpTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流（Obsidian 54 号）R19、R20：Redis 坏了，商店照常开门（fail-open），只是暂时不限流，打 ALERT 让人去修。
 *
 * 为什么不 fail-closed（Redis 坏了就 503）：那样 Redis 一坏就没人能登录、注册，等于整个商店下线。
 * 限流是多一道防护，不是主功能。⚠️ 54 号里标了"待 Christy 确认"。
 *
 * 【怎么让 Redis 坏】暂停 Redis 容器（docker pause）：连接还在，但什么都不回，比"连不上"更难对付——
 *   没有超时的话，每个请求会一直等下去。测完一定恢复（finally），后面的测试类还要用它。
 * 【红灯为什么红】现在没有限流，登录照样成功，但没有 ALERT；Spring Boot 会自动给 Redis 加健康检查，
 *   Redis 卡住时 /actuator/health 等不回来（默认超时 60 秒）。
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "management.endpoint.health.show-components=always") // R20 reads the component list
class RateLimitRedisDownIntegrationTest extends StoreHttpTest {

    private static final Duration ANSWER_WITHIN = Duration.ofSeconds(5);

    private static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private Account activeAccount(String password) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account account = new Account();
        account.setEmail("redis-down-" + tag + "@test.local");
        account.setUsername("redis-down-" + tag);
        account.setPassword(new BCryptPasswordEncoder().encode(password));
        account.setEmailVerified(true);
        account.setActive(true);
        account.setBankAccountNumber("ACC-" + tag);
        return accountRepository.save(account);
    }

    /** R19：Redis 卡住 → 登录照样成功，几秒内回答，打 ALERT rate_limit_unavailable。现在：成功了，但没有 ALERT。 */
    @Test
    void redisHangs_loginStillWorks_quickly_andAlerts(CapturedOutput output) {
        Account account = activeAccount("pw-123");
        pauseRedis();
        try {
            assertTimeoutPreemptively(ANSWER_WITHIN, () -> mvc.perform(post("/api/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + account.getEmail() + "\",\"password\":\"pw-123\"}"))
                    .andExpect(status().isOk()));
        } finally {
            unpauseRedis();
        }
        assertThat(output.getAll()).contains("ALERT rate_limit_unavailable");
    }

    /** R19：Redis 卡住 → 要验证码照样回答（没这个账号 → 200，不发信），不是 500 / 503。现在：401（store 还没这个接口）。 */
    @Test
    void redisHangs_sendVerificationStillAnswers() {
        pauseRedis();
        try {
            assertTimeoutPreemptively(ANSWER_WITHIN, () -> mvc.perform(post("/api/user/send-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@test.local\"}"))
                    .andExpect(status().isOk()));
        } finally {
            unpauseRedis();
        }
    }

    /**
     * R20：Redis 不算 store 健康的一部分。compose 的 healthcheck 用 /actuator/health（k8s 用 liveness / readiness
     * 两个组，本来就不含 Redis）：要是含 Redis，Redis 一坏 store 就被标成不健康。
     * 这个类打开了 show-components（见类上的 @TestPropertySource），健康检查的回答里列出每个组成部分：里面不能有 redis。
     * 测试环境里 RabbitMQ 故意指向没人听的端口，整体本来就是 DOWN，所以不看 UP，只看 redis 在不在、暂停后几秒内回答。
     * 红灯时：Spring Boot 自动加了 redis 这一项，暂停后还会等它（默认超时 60 秒）。
     */
    @Test
    void redisIsNotPartOfStoreHealth_evenWhenItHangs() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.redis").doesNotExist());

        pauseRedis();
        try {
            assertTimeoutPreemptively(ANSWER_WITHIN, () -> mvc.perform(get("/actuator/health"))
                    .andExpect(jsonPath("$.components.redis").doesNotExist()));
        } finally {
            unpauseRedis();
        }
    }
}
