package comp5348.storeservice.adapter;

import comp5348.storeservice.dto.DeliveryRequestDTO;
import comp5348.storeservice.dto.DeliveryResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class DeliveryAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(DeliveryAdapter.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    private final RestTemplate restTemplate;
    
    @Value("${delivery.service.url:http://localhost:8084}")
    private String deliveryServiceUrl;
    
    @SuppressWarnings("deprecation")
    public DeliveryAdapter(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
    
    /**
     * 创建配送请求 - 带重试机制
     */
    public DeliveryResponseDTO createDelivery(DeliveryRequestDTO request) {
        logger.info("Calling Delivery service API: orderId={}", request.getOrderId());
        
        String url = deliveryServiceUrl + "/api/deliveries/create";
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<DeliveryRequestDTO> entity = new HttpEntity<>(request, headers);
                
                ResponseEntity<DeliveryResponseDTO> response = restTemplate.postForEntity(
                        url, entity, DeliveryResponseDTO.class);
                
                DeliveryResponseDTO result = response.getBody();
                if (result != null) {
                    logger.info("Delivery request successful: orderId={}, deliveryId={}", 
                            request.getOrderId(), result.getDeliveryId());
                    return result;
                }
                
                // If response body is null but status is successful, create a success response
                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("Delivery request successful (empty response): orderId={}", request.getOrderId());
                    return new DeliveryResponseDTO(true, null, "Delivery created successfully");
                }
                
            } catch (Exception e) {
                logger.error("Delivery request attempt {} failed: {}", attempt, e.getMessage());
                
                if (attempt == MAX_RETRIES) {
                    logger.error("Delivery request failed after {} attempts for orderId={}", 
                            MAX_RETRIES, request.getOrderId());
                    return new DeliveryResponseDTO(false, null, 
                            "Delivery service unavailable: " + e.getMessage());
                }
                
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        return new DeliveryResponseDTO(false, null, "Delivery request failed after retries");
    }
}

