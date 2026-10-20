package comp5348.storeservice.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * store's only HTTP call to delivery since wave 2 step 4b: asking it to cancel before store cancels a paid order.
 * Creating a delivery is no longer an HTTP call: delivery reads OrderPaid from Kafka.
 */
@Component
public class DeliveryAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryAdapter.class);

    private final RestTemplate restTemplate;

    @Value("${delivery.service.url:http://localhost:8081}")
    private String deliveryServiceUrl;

    public DeliveryAdapter(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                    return factory;
                })
                .build();
    }

    /** What delivery said to a cancel request */
    public enum CancelAnswer {
        /** 200: CANCELLED_BEFORE_CREATED, CANCELLED or ALREADY_CANCELLED. Delivery will not ship this order. */
        AGREED,
        /** 409 TOO_LATE: the parcel is already picked up */
        TOO_LATE,
        /** No answer, or an error: we cannot tell whether delivery stopped it */
        UNAVAILABLE
    }

    /** POST /internal/deliveries/cancel-by-order/{orderId} (the design's cancel API section) */
    public CancelAnswer cancelByOrderId(String orderId) {
        String url = deliveryServiceUrl + "/internal/deliveries/cancel-by-order/" + orderId;
        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, null, String.class);
            logger.info("Delivery cancel for order {}: {} {}", orderId, resp.getStatusCode(), resp.getBody());
            return resp.getStatusCode().is2xxSuccessful() ? CancelAnswer.AGREED : CancelAnswer.UNAVAILABLE;
        } catch (HttpClientErrorException.Conflict e) {
            logger.info("Delivery says too late to cancel order {}: {}", orderId, e.getResponseBodyAsString());
            return CancelAnswer.TOO_LATE;
        } catch (Exception e) {
            logger.warn("Cancel delivery by orderId {} failed: {}", orderId, e.getMessage());
            return CancelAnswer.UNAVAILABLE;
        }
    }
}
