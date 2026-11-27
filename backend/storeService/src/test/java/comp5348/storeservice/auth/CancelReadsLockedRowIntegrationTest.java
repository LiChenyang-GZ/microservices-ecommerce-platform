package comp5348.storeservice.auth;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 鉴权 PR ② 独立审查 🟠1（44 号）：取消要按**锁住以后**的订单状态判断，不能用同一个事务里早先读到的旧值。
 *
 * 【怎么坏的】OrderProductService#cancelOwnOrder 先调 #ownOrder 查归属，这一步把 Order 实体读进了事务的
 *   持久化上下文。之后 OrderService#cancelOrder 用 findByIdForUpdate 加行锁，Hibernate 确实发了 SELECT … FOR UPDATE，
 *   但看到这个 id 已经在上下文里，就**直接返回旧实例**，不拿数据库里的新值。
 *   结果：订单主人双击取消，第二个请求等到锁后看到的还是 PAID，又取消一遍（多一条 OrderCancelled、多一封邮件）；
 *   或者读和锁之间 delivery 推进了状态 / 写了 deliveryId，取消照样进行、保存时把新值盖回旧值。
 * 【层】集成测试，单线程、确定性：在一个事务里先读（和 ownOrder 一样），中间用另一个事务把订单改成 DELIVERED 并提交，
 *   再调 cancelOrder。不用起两个线程去碰运气。
 * 【红灯为什么红】cancelOrder 拿到的是旧的 PENDING_PAYMENT，照样取消，不抛异常。
 */
class CancelReadsLockedRowIntegrationTest extends StoreIntegrationTest {

    @Autowired OrderService orderService;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void cancel_decidesOnTheLockedRow_notOnAnEarlierReadInTheSameTransaction() {
        PlacedOrder order = placeOrder();
        TransactionTemplate outer = new TransactionTemplate(txManager);
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(tx -> {
            // 1. 和 cancelOwnOrder 一样：先查归属，订单实体进了这个事务的上下文
            orderProductService.ownOrder(order.orderId(), buyerIdOf(order));

            // 2. 这时别的事务（比如 delivery 事件的消费者）把订单推进到 DELIVERED 并提交
            other.executeWithoutResult(t -> jdbc.update(
                    "UPDATE orders SET status = 'DELIVERED' WHERE id = ?", order.orderId()));

            // 3. 加锁取消：锁住以后看到的应该是 DELIVERED，不能取消
            assertThatThrownBy(() -> orderService.cancelOrder(order.orderId()))
                    .hasMessageContaining("Cannot cancel order with status");
            tx.setRollbackOnly();
        });

        assertThat(orderOf(order).getStatus()).as("已送达的订单没被改回取消").isEqualTo(OrderStatus.DELIVERED);
    }
}
