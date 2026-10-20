package com.comp5348.delivery.events;

import com.comp5348.delivery.support.DeliveryIntegrationTest;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 A1（丢件那一步）：配送单变成 LOST，一定发出 Lost 事件 —— store 要靠它退款（时序图 B）。
 *
 * 【层】集成测试，和 DeliveryEventPublishingIntegrationTest 一样。
 * 【为什么单独一个类】要把丢件率设成 100%，这是一个配置项，改了配置 Spring 会另起一个容器上下文。
 * 【红灯为什么红】1. 没有 Lost 事件（delivery 还不往 Kafka 发）。
 *   2. 丢件率现在写死在代码里（0%），这个配置键还没人读，所以配送单会走到 PICKED_UP 而不是 LOST。
 *   第 2 点是"测试用的开关还没接上"，不是要证明的问题本身；绿灯时把丢件率改成可配置。
 */
@TestPropertySource(properties = "delivery.simulation.loss-rate=1.0")
class DeliveryLostEventIntegrationTest extends DeliveryIntegrationTest {

    @Test
    void lostParcel_publishesLost() {
        String orderId = newOrderId();
        Long deliveryId = createDelivery(orderId);

        worker.processDelivery(deliveryId); // 丢件率 100%：这一步一定丢

        // 先看事件（这是要证明的东西），再看状态
        assertThat(awaitEventTypes(orderId, 2)).containsExactly("DeliveryCreated", "Lost");
        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.LOST);
    }
}
