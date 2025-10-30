package comp5348.storeservice.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class EmailAdapter {

    private static final Logger logger = LoggerFactory.getLogger(EmailAdapter.class);

    private final RestTemplate restTemplate;

    @Value("${email.service.url:http://localhost:8083}")
    private String emailServiceUrl;

    public EmailAdapter(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                    return factory;
                })
                .build();
    }

    public void sendOrderCancelled(String email, String orderId, String reason) {
        postJson("/api/email/send-order-cancelled", email, orderId, reason);
    }

    public void sendOrderFailed(String email, String orderId, String reason) {
        postJson("/api/email/send-order-failed", email, orderId, reason);
    }

    public void sendRefundSuccess(String email, String orderId, String refundTxnId) {
        try {
            String url = emailServiceUrl + "/api/email/send-refund-success";
            Map<String, Object> body = new HashMap<>();
            body.put("email", email);
            body.put("orderId", orderId);
            body.put("refundTxnId", refundTxnId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            logger.warn("Email refund notify failed for order {}: {}", orderId, e.getMessage());
        }
    }

    private void postJson(String path, String email, String orderId, String reason) {
        try {
            String url = emailServiceUrl + path;
            Map<String, Object> body = new HashMap<>();
            body.put("email", email);
            body.put("orderId", orderId);
            body.put("reason", reason);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            logger.warn("Email notify failed for order {}: {}", orderId, e.getMessage());
        }
    }
}


