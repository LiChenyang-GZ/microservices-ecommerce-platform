package com.comp5348.delivery.contracts;

import com.comp5348.contracts.events.DeliveryEventType;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 层级：单元测试（只用两个枚举，不启动 Spring）。
 * 为什么放在 delivery 里而不是 contracts 里：DeliveryStatus 是 delivery 自己的枚举，
 * contracts 看不到它。放在这里，以后谁给 DeliveryStatus 加一个新状态却忘了加事件，这个测试就会红。
 */
class DeliveryEventContractTest {

    @Test
    void everyDeliveryStatus_hasMatchingEventType() {
        // 遍历 delivery 现有的每一个状态。
        for (DeliveryStatus status : DeliveryStatus.values()) {
            // 每个状态都要能找到对应的事件，不能抛异常。
            // 名字不是一一相同的：CREATED 对应 DeliveryCreated，所以不能直接 valueOf(name)。
            assertThatCode(() -> DeliveryEventType.fromStatus(status.name()))
                    .as("DeliveryStatus.%s has no event type", status)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void deliveryStatusToEvent_isOneToOne() {
        // 把每个状态映射成事件，收集成 Set（自动去重）。
        Set<DeliveryEventType> mapped = Arrays.stream(DeliveryStatus.values())
                .map(s -> DeliveryEventType.fromStatus(s.name()))
                .collect(Collectors.toSet());

        // 一一对应：状态有几个，不重复的事件就有几个；并且没有哪个事件是多余的。
        assertThat(mapped).hasSize(DeliveryStatus.values().length);
        assertThat(mapped).containsExactlyInAnyOrder(DeliveryEventType.values());
    }
}
