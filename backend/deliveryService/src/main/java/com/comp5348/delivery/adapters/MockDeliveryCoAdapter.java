package com.comp5348.delivery.adapters;

import org.springframework.context.annotation.Profile; // Import Profile annotation
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@Profile("dev") // Key annotation! Indicates this Bean is only active in development (dev) environment
public class MockDeliveryCoAdapter implements DeliveryAdapter {

    @Override // Indicates this is an implementation of the interface method
    public String requestDelivery(String orderId) {
        System.out.println("[Mock Adapter MOCK]: Calling DeliveryCo API for delivery request for order ID '" + orderId + "'...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String mockCarrierReference = "MOCK-TRACKING-" + UUID.randomUUID().toString().substring(0, 8);
        System.out.println("[Mock Adapter MOCK]: DeliveryCo API call successful, returned tracking number: " + mockCarrierReference);
        return mockCarrierReference;
    }
}