package com.comp5348.delivery.events;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.support.DeliveryIntegrationTest;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * 第 2 波集成发现 I1（[[29-第2波集成-进度与发现]]）：配送作业发不进 RabbitMQ 时，配送单永远卡住。
 *
 * 集成时真的复现了（scenarios/05-rabbitmq-down-at-delivery.ps1，订单 16）：
 *   delivery 读到 OrderPaid → 建单并提交 → afterCommit 发作业时 RabbitMQ 挂着，抛异常
 *   → Kafka 重投 OrderPaid → "这单已经有配送单了"，跳过 → 没有任何东西再发作业，配送单停在 CREATED。
 * 同一类的第二处：worker 每推进一步、提交之后，再把作业放回队列；这一刻 RabbitMQ 挂了，单子停在中间状态。
 * 根子：数据库提交和发 RabbitMQ 是两步，不在一个事务里，中间断了没人补。
 *
 * 要的行为（Christy 2026-10-21 选了修法 A：扫表补发）：
 *   不是终态（RECEIVED / LOST / CANCELLED 以外）、超过一段时间没动的配送单，delivery 自己把作业重新发进队列。
 *   测试里"一段时间" = 2 秒（基类的 delivery.stuck-job.stale-after-ms）。
 *
 * 【层】集成测试。RabbitTemplate 是 Mockito 假对象：rabbitDown = true 时每次发作业都抛连接异常（扮演 RabbitMQ 挂了），
 *   false 时把发出的配送单 ID 记下来（扮演 RabbitMQ 收到了）。
 * 【只看外面能看到的】"RabbitMQ 恢复后，这张单的作业有没有被重新发出来"——不关心是谁、用什么方式发的。
 * 【红灯为什么红】现在没有任何代码会重新发作业，前两个测试等满 10 秒后失败。第三个是守护测试，红灯时就是绿的。
 */
class StuckDeliveryJobIntegrationTest extends DeliveryIntegrationTest {

    private volatile boolean rabbitDown;
    private final AtomicInteger failedSends = new AtomicInteger();
    /** RabbitMQ "收到"的配送单 ID（后台线程写，测试线程读） */
    private final List<Long> jobsSent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void fakeRabbit() {
        doAnswer(invocation -> {
            if (rabbitDown) {
                failedSends.incrementAndGet();
                throw new AmqpConnectException(new ConnectException("RabbitMQ is down (test)"));
            }
            jobsSent.add((Long) invocation.getArgument(1));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.DELIVERY_QUEUE_NAME), any(Object.class));
    }

    /** 场景 05 本身：建单那一刻 RabbitMQ 挂着 */
    @Test
    void jobLostWhenCreated_isSentAgain_afterRabbitComesBack() {
        String orderId = newOrderId();
        rabbitDown = true;

        publishOrderEvent(orderPaid(orderId));
        awaitDeliveryRow(orderId);
        await().atMost(EVENT_TIMEOUT).until(() -> failedSends.get() > 0); // 建单后的那次发作业失败了
        Long deliveryId = deliveryOf(orderId).getId();
        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CREATED);

        rabbitDown = false; // RabbitMQ 回来了

        await().atMost(EVENT_TIMEOUT)
                .untilAsserted(() -> assertThat(jobsSent).as("卡在 CREATED 的配送单，作业要被重新发出来").contains(deliveryId));
    }

    /** 第二处：worker 推进一步并提交后，把作业放回队列时 RabbitMQ 挂了 */
    @Test
    void jobLostBetweenSteps_isSentAgain_afterRabbitComesBack() {
        String orderId = newOrderId();
        Long deliveryId = createDelivery(orderId); // RabbitMQ 正常时建的单
        jobsSent.clear();
        rabbitDown = true;

        try {
            worker.processDelivery(deliveryId); // CREATED → PICKED_UP 已提交，放回队列时失败
        } catch (RuntimeException expected) {
            // worker 重试几次发不出去后把异常抛给 RabbitMQ 监听器（真环境里进死信队列）；这里不关心
        }
        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.PICKED_UP);

        rabbitDown = false;

        await().atMost(EVENT_TIMEOUT)
                .untilAsserted(() -> assertThat(jobsSent).as("停在 PICKED_UP 的配送单，作业要被重新发出来").contains(deliveryId));
    }

    /** 守护测试：终态的单不能被"补发"，否则已经取消的单会被重新推进（红灯时就是绿的） */
    @Test
    void finishedDelivery_isNeverSentAgain() throws InterruptedException {
        String orderId = newOrderId();
        deliveryService.cancelByOrderId(orderId); // 还没建单就取消：留下一张 CANCELLED 的标记行
        Long deliveryId = deliveryOf(orderId).getId();

        Thread.sleep(4_000); // 两倍的"卡住"时长，补发如果会发生，早就发生了

        assertThat(jobsSent).as("CANCELLED 是终态，不补发").doesNotContain(deliveryId);
    }
}
