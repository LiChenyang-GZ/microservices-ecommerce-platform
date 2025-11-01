package com.comp5348.delivery.adapters;

/**
 * Unified interface for interacting with external delivery services (e.g., DeliveryCo).
 * Whether we use a mock implementation or a real API call implementation in the future,
 * they must all comply with the specifications defined by this interface.
 */
public interface DeliveryAdapter {

    /**
     * Initiate a delivery request to the delivery service provider.
     *
     * @param orderId Order ID that needs to be delivered
     * @return Unique tracking number (Carrier Reference) returned by the delivery service provider
     */
    String requestDelivery(String orderId);

    // If there are other delivery-related operations in the future, such as "cancel delivery request", they can also be defined here
    // void cancelDelivery(String carrierRef);
}