package com.comp5348.delivery.adapters;

/**
 * 这是与外部物流服务(如 DeliveryCo)交互的统一接口。
 * 无论我们将来使用模拟的实现(Mock)还是真实的API调用实现，
 * 它们都必须遵守这个接口定义的规范。
 */
public interface DeliveryAdapter {

    /**
     * 向物流服务商发起一个配送请求。
     *
     * @param orderId 需要配送的订单ID
     * @return 由物流服务商返回的唯一追踪号 (Carrier Reference)
     */
    String requestDelivery(String orderId);

    // 将来如果还有其他与物流相关的操作，比如“取消配送请求”，也可以在这里定义
    // void cancelDelivery(String carrierRef);
}