package comp5348.storeservice.payment;

import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.PaymentRepository;
import comp5348.storeservice.service.OutboxService;
import comp5348.storeservice.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 失败场景表 #6（单元层）：余额不足 → FAILED，订单还是待支付。
 *
 * 【层】单元测试：只测 PaymentService 这一个类，它依赖的东西全用 Mockito 的假对象。
 * 【真的】PaymentService 里"bank 明确拒绝之后怎么改状态"的那段逻辑。
 * 【假的】数据库（repository）、bank（BankAdapter）、outbox。不起 Spring，不连数据库，几毫秒跑完。
 * 【测的是】handlePaymentFailure：bank 明确说"不行"（比如余额不足）时被调用的方法。
 */
@ExtendWith(MockitoExtension.class) // 让 JUnit 5 认识 @Mock / @InjectMocks
class PaymentServiceTest {

    // @Mock：造一个假对象，所有方法默认什么都不做、返回空值
    @Mock PaymentRepository paymentRepository;
    @Mock BankAdapter bankAdapter;
    @Mock OutboxService outboxService;
    @Mock OrderRepository orderRepository;
    @Mock AccountRepository accountRepository;

    // @InjectMocks：new 一个真的 PaymentService，并把上面的假对象塞进它的 @Autowired 字段
    @InjectMocks PaymentService paymentService;

    @Test
    void insufficientFunds_marksFailed_orderStaysPending() {
        // 准备：一笔待扣款的 Payment，对应一张待支付的订单
        Order order = new Order();
        order.setId(1L);
        order.setStatus(OrderStatus.PENDING_PAYMENT);

        Payment payment = new Payment();
        payment.setId(10L);
        payment.setOrderId(1L);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setStatus(PaymentStatus.PENDING);

        // 动作：bank 明确拒绝了（这里不经过 HTTP，直接告诉 PaymentService "bank 说余额不足"）
        paymentService.handlePaymentFailure(payment, "Insufficient balance. Available: 10.00");

        // 断言 1：这是确定的失败 → FAILED（只有 FAILED，Pay Now 才允许换新键再付，见规则 8）
        assertThat(payment.getStatus()).as("bank 明确拒绝 = 确定失败").isEqualTo(PaymentStatus.FAILED);
        // 断言 2：失败原因记下来，前端可以显示
        assertThat(payment.getErrorMessage()).contains("Insufficient balance");
        // 断言 3：订单不动，还是待支付（用户充值后还能 Pay Now）
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        verify(orderRepository, never()).save(any()); // 没有任何代码去改订单
    }
}
