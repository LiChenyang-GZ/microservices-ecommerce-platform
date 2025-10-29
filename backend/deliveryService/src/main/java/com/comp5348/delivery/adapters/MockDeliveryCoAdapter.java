package com.comp5348.delivery.adapters;

import org.springframework.context.annotation.Profile; // 导入Profile注解
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@Profile("dev") // 关键注解！表示这个Bean只在开发(dev)环境下激活
public class MockDeliveryCoAdapter implements DeliveryAdapter {

    @Override // 表明这是对接口方法的实现
    public String requestDelivery(String orderId) {
        System.out.println("【模拟适配器 MOCK】: 正在为订单ID '" + orderId + "' 调用 DeliveryCo API 请求配送...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String mockCarrierReference = "MOCK-TRACKING-" + UUID.randomUUID().toString().substring(0, 8);
        System.out.println("【模拟适配器 MOCK】: DeliveryCo API 调用成功，返回的追踪号是: " + mockCarrierReference);
        return mockCarrierReference;
    }
}