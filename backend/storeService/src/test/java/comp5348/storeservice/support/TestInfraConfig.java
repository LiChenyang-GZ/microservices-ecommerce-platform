package comp5348.storeservice.support;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 集成测试专用的两个替身。
 */
@TestConfiguration
public class TestInfraConfig {

    /**
     * 可以手动拨的表。@Primary：以后生产代码也定义了 Clock，测试里优先用这个。
     * 注意：现在的生产代码还在用 LocalDateTime.now()，根本不看 Clock——这正是要修的地方之一。
     */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(Instant.now(), ZoneId.systemDefault());
    }

    /**
     * 一个"永远不触发"的定时器。
     * @Scheduled 的任务（OutboxProcessor 每 5 秒、UnpaidOrderCanceller 每 60 秒）都交给名叫 taskScheduler 的 bean 去排。
     * 这里给它一个 Mockito 假对象：收到排期请求就假装答应，但永远不执行。
     * 这样后台不会自己跑，测试想跑一轮就自己调 processOutbox()，结果才是确定的。
     * RETURNS_MOCKS：schedule 方法要返回一个 ScheduledFuture，就返回一个假的，避免关闭时空指针。
     */
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        return Mockito.mock(TaskScheduler.class, Mockito.RETURNS_MOCKS);
    }
}
