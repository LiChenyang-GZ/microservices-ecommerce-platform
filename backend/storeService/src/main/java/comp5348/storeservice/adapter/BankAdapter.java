package comp5348.storeservice.adapter;

import comp5348.storeservice.dto.BankTransferRequest;
import comp5348.storeservice.dto.BankTransferResponse;
import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankRefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;

@Component
public class BankAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(BankAdapter.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;
    
    private final RestTemplate restTemplate;
    
    @Value("${bank.service.url:http://localhost:8084}")
    private String bankServiceUrl;
    
    public BankAdapter(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
    
    /**
     * 转账接口 - 带重试机制
     */
    public BankTransferResponse transfer(BankTransferRequest request) {
        logger.info("Calling Bank transfer API: ref={}", request.getTransactionRef());
        
        String url = bankServiceUrl + "/api/bank/transfer";
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<BankTransferRequest> entity = new HttpEntity<>(request, headers);
                
                ResponseEntity<BankTransferResponse> response = restTemplate.postForEntity(url, entity, BankTransferResponse.class);
                
                BankTransferResponse result = response.getBody();
                if (result != null) {
                    logger.info("Bank transfer response: success={}, txnId={}, message={}", 
                            result.isSuccess(), result.getTransactionId(), result.getMessage());
                    return result;
                }
                
            } catch (Exception e) {
                logger.error("Bank transfer attempt {} failed: {}", attempt, e.getMessage());
                
                if (attempt == MAX_RETRIES) {
                    logger.error("Bank transfer failed after {} attempts", MAX_RETRIES);
                    return new BankTransferResponse(false, null, "Bank service unavailable: " + e.getMessage());
                }
                
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        return new BankTransferResponse(false, null, "Bank transfer failed after retries");
    }
    
    /**
     * 退款接口 - 带重试机制
     */
    public BankRefundResponse refund(BankRefundRequest request) {
        logger.info("Calling Bank refund API: transactionId={}", request.getTransactionId());
        
        String url = bankServiceUrl + "/api/bank/refund";
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<BankRefundRequest> entity = new HttpEntity<>(request, headers);
                
                ResponseEntity<BankRefundResponse> response = restTemplate.postForEntity(url, entity, BankRefundResponse.class);
                
                BankRefundResponse result = response.getBody();
                if (result != null) {
                    logger.info("Bank refund response: success={}, refundTxnId={}, message={}", 
                            result.isSuccess(), result.getRefundTransactionId(), result.getMessage());
                    return result;
                }
                
            } catch (Exception e) {
                logger.error("Bank refund attempt {} failed: {}", attempt, e.getMessage());
                
                if (attempt == MAX_RETRIES) {
                    logger.error("Bank refund failed after {} attempts", MAX_RETRIES);
                    return new BankRefundResponse(false, null, "Bank service unavailable: " + e.getMessage());
                }
                
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        return new BankRefundResponse(false, null, "Bank refund failed after retries");
    }
}


