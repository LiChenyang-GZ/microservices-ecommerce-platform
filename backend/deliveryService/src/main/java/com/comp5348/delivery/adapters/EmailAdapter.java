package com.comp5348.delivery.adapters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class EmailAdapter {

    private static final Logger logger = LoggerFactory.getLogger(EmailAdapter.class);

    private final RestTemplate restTemplate;

    @Value("${email.service.url:http://localhost:8083}")
    private String emailServiceUrl;

    public EmailAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void sendDeliveryUpdate(String email, String orderId, Long deliveryId, String status) {
        try {
            String url = emailServiceUrl + "/api/email/send-delivery-update";
            Map<String, Object> body = new HashMap<>();
            body.put("email", email);
            body.put("orderId", orderId);
            body.put("deliveryId", deliveryId);
            body.put("status", status);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, entity, Map.class);
            logger.info("Email notification sent for delivery {} -> status {}", deliveryId, status);
        } catch (Exception e) {
            logger.warn("Failed to send email notification for delivery {}: {}", deliveryId, e.getMessage());
        }
    }
}


