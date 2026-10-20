package com.comp5348.delivery.events;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.support.DeliveryIntegrationTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * 失败场景表 B12（🟠 设计没写，Christy 同意按默认 ❓7）：新配送单的推进作业要在事务**提交之后**发进 RabbitMQ，而且只发一次。
 *
 * 旧代码：`createDelivery` 在事务里就把配送单 ID 发进队列，而且同一个队列发了两次。
 *   worker 如果比提交快，就查不到这一行，直接丢弃消息 → 配送单永远停在 CREATED。
 *   发两次 → 两个 worker 同时推进同一张单，靠乐观锁冲突丢掉一个。
 *
 * 【层】集成测试。RabbitTemplate 是 Mockito 假对象：每次"发作业"时，测试自己开一个新的数据库连接去查这一行——
 *   查得到 = 已经提交了；查不到 = 还在事务里就发了（就是 bug）。
 * 【怎么触发建单】4b 起建单只来自 OrderPaid，所以测试发一条 OrderPaid。
 * 【红灯为什么红】delivery 还不读 order-events，根本不建单；就算建了，现在也是提交前发、发两次。
 */
class DeliveryJobAfterCommitIntegrationTest extends DeliveryIntegrationTest {

    @Test
    void newDelivery_jobSentOnce_afterCommit() throws Exception {
        String orderId = newOrderId();
        // 每次往配送作业队列发 ID 时，记下"发的是哪张单、这时候从别的连接看，这个订单的行在不在"
        // 要按 ID 过滤：I1 的扫表补发会给别的测试留下的、没走完的单补发作业，它们也经过这个假对象
        List<Map.Entry<Long, Boolean>> sends = new CopyOnWriteArrayList<>(); // 消费者线程写，测试线程读
        doAnswer(invocation -> {
            sends.add(Map.entry((Long) invocation.getArgument(1), rowCommitted(orderId)));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.DELIVERY_QUEUE_NAME), any(Object.class));

        publishOrderEvent(orderPaid(orderId));
        awaitDeliveryRow(orderId);
        Long deliveryId = deliveryOf(orderId).getId();
        await().atMost(EVENT_TIMEOUT).until(() -> !visibleWhenSent(sends, deliveryId).isEmpty());
        Thread.sleep(500); // 如果有第二次发送，它就紧跟在第一次后面（扫表要 2 秒没动才补发，不会落在这 500 毫秒里）

        assertThat(visibleWhenSent(sends, deliveryId)).as("只发一次，而且发的时候这一行已经提交了").containsExactly(true);
    }

    private static List<Boolean> visibleWhenSent(List<Map.Entry<Long, Boolean>> sends, Long deliveryId) {
        return sends.stream().filter(s -> s.getKey().equals(deliveryId)).map(Map.Entry::getValue).toList();
    }

    /** 用一个全新的连接查：只看得到已经提交的数据 */
    private boolean rowCommitted(String orderId) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM delivery WHERE order_id = ?")) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
