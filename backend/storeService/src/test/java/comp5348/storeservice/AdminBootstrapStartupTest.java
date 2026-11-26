package comp5348.storeservice;

import comp5348.storeservice.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权 PR ③：第一个管理员从哪来（36 号 E6），老库里的账号怎么补角色（E7）。
 *
 * 【层】启动测试，和 StoreStartupWithoutServiceTokenTest 一个写法：测试自己把 store 整个启动一次，再用 JDBC 看库里有什么。
 *   真 Postgres、真 Kafka；RabbitMQ 指向没人监听的端口；OutboxProcessor 关掉。
 * 【约定】store.admin.email、store.admin.password（部署时来自 ADMIN_EMAIL、ADMIN_PASSWORD）：
 *   - 都配了、库里没有这个邮箱 → 建一个已激活的管理员，密码是 BCrypt；
 *   - 库里已经有这个邮箱的普通账号 → **不提升**、打 WARN（激活不查验证码 A11，任何人都能抢先注册这个邮箱）；
 *   - 没配 → 不建，照常启动；
 *   - 老账号 role 是 NULL → 启动时补成 USER。
 * 【红灯为什么红】store 不读这两个键、Account 没有 role 列：没建出管理员；查 role 列时 SQL 报"列不存在"。不是编译错。
 */
class AdminBootstrapStartupTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    static final JdbcTemplate DB;

    static {
        POSTGRES.start();
        KAFKA.start();
        DB = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void configuredAdmin_isCreated_activeWithAnEncodedPassword() {
        String email = "admin-" + tag() + "@test.local";

        startStore(Map.of("store.admin.email", email, "store.admin.password", "Adm1n-Passw0rd"));

        List<Map<String, Object>> rows = DB.queryForList("SELECT * FROM account WHERE email = ?", email);
        assertThat(rows).as("建出了管理员账号").hasSize(1);
        Map<String, Object> admin = rows.get(0);
        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(admin.get("email_verified")).isEqualTo(true);
        assertThat(admin.get("active")).isEqualTo(true);
        assertThat(new BCryptPasswordEncoder().matches("Adm1n-Passw0rd", (String) admin.get("password")))
                .as("密码存的是 BCrypt，不是明文").isTrue();
    }

    @Test
    void existingUserWithTheAdminEmail_isNotPromoted() {
        String email = "taken-" + tag() + "@test.local";
        startStore(Map.of()); // 先启动一次，让 Hibernate 建表
        DB.update("INSERT INTO account (email, username, password, email_verified, active) VALUES (?, ?, 'his-own', true, true)",
                email, "taken-" + tag());

        startStore(Map.of("store.admin.email", email, "store.admin.password", "Adm1n-Passw0rd"));

        assertThat(DB.queryForObject("SELECT role FROM account WHERE email = ?", String.class, email))
                .as("抢先注册的普通账号没有变成管理员").isEqualTo("USER");
        assertThat(DB.queryForObject("SELECT password FROM account WHERE email = ?", String.class, email))
                .as("也没被改密码").isEqualTo("his-own");
    }

    /** 74 号 E7：ADMIN_EMAIL 里有大写 → 存成小写，管理员登录时输哪种大小写都能找到。现在：原样存成大写。 */
    @Test
    void configuredAdminEmailInCapitals_isStoredInOneSpelling() {
        String lower = "boss-" + tag() + "@test.local";

        startStore(Map.of("store.admin.email", "Boss" + lower.substring(4, 13).toUpperCase() + "@Test.Local",
                "store.admin.password", "Adm1n-Passw0rd"));

        assertThat(DB.queryForObject("SELECT role FROM account WHERE email = ?", String.class, lower)).isEqualTo("ADMIN");
    }

    /** 74 号 E7 的后半句（审查 75 号 🟡6）：这样建出来的管理员，输小写、全大写都能登录。回归保护，现在就该绿 */
    @Test
    void configuredAdminEmailInCapitals_adminLogsInWithAnyCase() {
        String lower = "chief-" + tag() + "@test.local";

        try (ConfigurableApplicationContext store = runStore(Map.of(
                "store.admin.email", "Chief" + lower.substring(5, 14).toUpperCase() + "@Test.Local",
                "store.admin.password", "Adm1n-Passw0rd"))) {
            AccountService accounts = store.getBean(AccountService.class);
            assertThat(accounts.verifyLogin(lower, "Adm1n-Passw0rd")).isTrue();
            assertThat(accounts.verifyLogin(lower.toUpperCase(), "Adm1n-Passw0rd")).isTrue();
        }
    }

    /**
     * 74 号 E7：库里已有小写 taken@ 的普通账号，ADMIN_EMAIL 配成 Taken@ → 和配成小写一样：不提升、不另建一个管理员。
     * 现在：查不到大写的，另建了一个同信箱的管理员账号（抢先注册的人等于和管理员共用一个信箱）。
     */
    @Test
    void existingUser_adminEmailInOtherCase_isNotPromoted_andNoSecondAccount() {
        String lower = "taken-" + tag() + "@test.local";
        startStore(Map.of());
        DB.update("INSERT INTO account (email, username, password, email_verified, active, role) VALUES (?, ?, 'his-own', true, true, 'USER')",
                lower, "taken-" + tag());

        startStore(Map.of("store.admin.email", lower.toUpperCase(), "store.admin.password", "Adm1n-Passw0rd"));

        assertThat(DB.queryForObject("SELECT count(*) FROM account WHERE lower(email) = ?", Integer.class, lower))
                .as("没有另建一个账号").isEqualTo(1);
        assertThat(DB.queryForObject("SELECT role FROM account WHERE email = ?", String.class, lower)).isEqualTo("USER");
    }

    /** 47 号 🟡5：原来的断言查的是没人会建的 never-% 邮箱，永远成立。改成启动前后数管理员总数 */
    @Test
    void notConfigured_noAdminIsCreated() {
        startStore(Map.of());
        Integer before = DB.queryForObject("SELECT count(*) FROM account WHERE role = 'ADMIN'", Integer.class);

        startStore(Map.of());

        assertThat(DB.queryForObject("SELECT count(*) FROM account WHERE role = 'ADMIN'", Integer.class))
                .as("没配就没有新管理员").isEqualTo(before);
    }

    /**
     * 47 号 🟠2：有人先注册了 用户名 = ADMIN_EMAIL 的账号（邮箱是他自己的）。AdminBootstrap 只查了邮箱，
     * 新建管理员时 username 撞唯一约束，CommandLineRunner 抛异常 → store 起不来。
     * 红：startStore 抛异常（DataIntegrityViolation）。期望：照常启动，管理员建出来（用别的用户名），抢注的账号不动。
     */
    @Test
    void someoneTookTheAdminEmailAsAUsername_storeStillStarts() {
        String adminEmail = "boss-" + tag() + "@test.local";
        String squatter = "squatter-" + tag() + "@test.local";
        startStore(Map.of());
        DB.update("INSERT INTO account (email, username, password, email_verified, active, role) VALUES (?, ?, 'x', true, true, 'USER')",
                squatter, adminEmail);

        startStore(Map.of("store.admin.email", adminEmail, "store.admin.password", "Adm1n-Passw0rd"));

        assertThat(DB.queryForObject("SELECT role FROM account WHERE email = ?", String.class, adminEmail))
                .as("管理员建出来了").isEqualTo("ADMIN");
        assertThat(DB.queryForObject("SELECT role FROM account WHERE email = ?", String.class, squatter))
                .as("抢注的账号还是普通用户").isEqualTo("USER");
    }

    /** E7：角色上线前就有的账号（role 是 NULL），重启后补成 USER */
    @Test
    void oldAccountsWithoutARole_becomeUsers() {
        String email = "old-" + tag() + "@test.local";
        startStore(Map.of());
        DB.update("INSERT INTO account (email, username, password, email_verified, active) VALUES (?, ?, 'x', true, true)",
                email, "old-" + tag());
        // 模拟老库：这一行没有角色（绿灯后列是存在的；红灯时这条 UPDATE 就会因为列不存在而报错）
        DB.update("UPDATE account SET role = NULL WHERE email = ?", email);

        startStore(Map.of());

        assertThat(DB.queryForObject("SELECT role FROM account WHERE email = ?", String.class, email)).isEqualTo("USER");
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 把 store 启动一次再关掉（失败就让测试失败） */
    private static void startStore(Map<String, String> extra) {
        try (ConfigurableApplicationContext ignored = runStore(extra)) {
            // started and stopped
        }
    }

    /** 启动 store 并让它开着（调用方负责关） */
    private static ConfigurableApplicationContext runStore(Map<String, String> extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--jwt.secret=test-only-secret-test-only-secret-0123456789abcdef",
                "--RABBITMQ_USERNAME=guest",
                "--RABBITMQ_PASSWORD=guest",
                "--spring.rabbitmq.port=1",
                "--outbox.processor.enabled=false",
                "--server.port=0",
                "--bank.service-token=test-bank-service-token",
                "--delivery.service-token=test-delivery-service-token",
                "--email.service-token=test-email-service-token",
                // 命令行优先级最高：本机 .env 里就算配了 ADMIN_EMAIL，这里也会盖掉（没配的测试显式给空）
                "--store.admin.email=" + extra.getOrDefault("store.admin.email", ""),
                "--store.admin.password=" + extra.getOrDefault("store.admin.password", "")));
        return new SpringApplicationBuilder(StoreServiceApplication.class).run(args.toArray(String[]::new));
    }
}
