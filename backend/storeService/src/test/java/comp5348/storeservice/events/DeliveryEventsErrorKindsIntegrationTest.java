package comp5348.storeservice.events;

import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.comp5348.contracts.events.DeliveryEventType.DELIVERY_CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

/**
 * 失败场景表 D7（Christy 选了 ❓1 的 b）+ D3 的分类部分：异常分三种，各自怎么处理。
 *   - 消息本身有问题 → 马上进 DLT（D1 测过）
 *   - 已知的暂时性异常（数据库连不上、超时）→ 无限退避重试，不进 DLT
 *   - 其他异常（多半是代码 bug，比如空指针）→ 重试几次（5 次）还不行就进 DLT + ALERT，不让一个 bug 永远堵住分区
 *
 * 【层】集成测试。
 * 【怎么制造异常】@MockitoSpyBean：把真的 OrderRepository 包一层"间谍"，平时照常工作，
 *   只对测试指定的那个订单号抛异常。绿灯实现里，消费者用 findByIdForUpdate 锁订单行（时序图 B"锁订单行"）。
 *   换了 spy 的上下文和别的测试类不一样，Spring 会另起一个上下文。
 * 【红灯为什么红】没有消费者：DLT 里没有东西，订单也不会被处理。
 */
class DeliveryEventsErrorKindsIntegrationTest extends DeliveryEventsTestBase {

    @MockitoSpyBean
    OrderRepository orderRepositorySpy;

    /** D7：代码 bug 类异常 → 有限次重试后进 DLT；既不是只试一次就放弃，也不是无限重试 */
    @Test
    void codeBug_retriedFewTimes_thenDlt() {
        PlacedOrder order = placePaidOrder(); // 下单、付款都要用真的仓库方法，所以先下单，再"埋 bug"
        doThrow(new IllegalStateException("simulated bug"))
                .when(orderRepositorySpy).findByIdForUpdate(order.orderId());

        publish(order, DELIVERY_CREATED);

        awaitDeadLetter(order.orderId().toString(), Duration.ofSeconds(60));
        // 重试了几次（不是 1 次就放弃），但有上限（不是无限）
        verify(orderRepositorySpy, atLeast(2)).findByIdForUpdate(order.orderId());
        verify(orderRepositorySpy, atMost(10)).findByIdForUpdate(order.orderId());
    }

    /**
     * D3 的分类：已知的暂时性异常连续出现 8 次（比 bug 的重试上限 5 次还多），仍然不能进 DLT；
     * 第 9 次数据库好了，事件照常处理。
     */
    @Test
    void knownTransientError_isRetriedBeyondTheBugLimit_andNotDlt() {
        PlacedOrder order = placePaidOrder();
        AtomicInteger calls = new AtomicInteger();
        // 前 8 次抛"数据库抖了一下"；第 9 次起照常查库。
        // OrderRepository 是接口，Spring 的 spy 把调用"转给"真的仓库对象（spy 的默认 answer），
        // 所以"照常"= 交回给这个默认 answer，不能用 doCallRealMethod（接口方法没有"真方法"可调）。
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 8) {
                throw new TransientDataAccessResourceException("db hiccup");
            }
            return mockingDetails(orderRepositorySpy).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(orderRepositorySpy).findByIdForUpdate(order.orderId());

        publish(order, DELIVERY_CREATED);

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                .until(() -> Long.valueOf(deliveryIdOf(order)).equals(orderOf(order).getDeliveryId()));
        assertThat(kafka.recordsOfOrder("delivery-events.DLT", order.orderId().toString())).isEmpty();
    }
}
