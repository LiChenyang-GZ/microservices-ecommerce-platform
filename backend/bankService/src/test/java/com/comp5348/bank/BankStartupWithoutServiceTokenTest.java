package com.comp5348.bank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bank 没配服务凭据就不能启动。失败场景表（Obsidian 36 号文件）E2。
 *
 * 反例："没配就不检查"。那样的话部署时漏了一个环境变量，bank 照常启动、看起来一切正常，
 *   其实扣款和退款的门又全开了，而且没有任何报错。启动失败才会有人发现。
 *
 * 【层】启动测试：不用 @SpringBootTest（它只能测"启动成功"的容器），而是测试自己调
 *   SpringApplicationBuilder(...).run(...) 把 bank 整个启动一次，看它是成功还是抛异常。真 Postgres。
 * 【参数怎么传】用命令行参数（--键=值）：优先级最高，盖得住本机 backend/.env 里的值。
 * 【红灯为什么是红】bank 现在根本不读 bank.service-token，空的也照常启动（startBank 返回 null）。不是编译错。
 * 【约定】配置项 bank.service-token 为空或只有空格 → 启动失败，异常信息里要有这个键名，
 *   这样看日志的人知道该去配什么。
 */
class BankStartupWithoutServiceTokenTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** 把 bank 启动一次再关掉。启动成功返回 null，启动失败返回那个异常。 */
    private static Throwable startBank(String serviceToken) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BankApplication.class).run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                // 端口 0 = 让操作系统挑空闲的，不和别的测试、本机在跑的 bank 撞
                "--server.port=0",
                "--bank.grpc.port=0",
                "--bank.service-token=" + serviceToken)) {
            return null;
        } catch (Throwable startupFailure) {
            return startupFailure;
        }
    }

    /** E2：凭据是空的，或者只有空格 → 启动失败，异常里点名 bank.service-token。 */
    @ParameterizedTest(name = "token = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    void blankServiceToken_failsStartup(String blank) {
        Throwable failure = startBank(blank);

        assertThat(failure).as("没配服务凭据，bank 不该启动成功").isNotNull();
        assertThat(failure).hasStackTraceContaining("bank.service-token");
    }

    /**
     * 对照：同样的启动方式，配了凭据就能启动。这条现在就是绿的。
     * 没有它的话，上面那条可能"假绿"：比如数据库地址传错了，bank 也会启动失败，但原因根本不是凭据。
     */
    @Test
    void withServiceToken_starts() {
        assertThat(startBank("test-bank-service-token")).as("配了凭据应该正常启动").isNull();
    }
}
