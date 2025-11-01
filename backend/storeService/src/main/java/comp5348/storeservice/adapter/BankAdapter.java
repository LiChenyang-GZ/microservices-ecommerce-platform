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
import org.springframework.http.client.SimpleClientHttpRequestFactory;

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
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                    return factory;
                })
                .build();
    }

    /**
     * Query account balance
     */
    public java.math.BigDecimal getBalance(String accountNumber) {
        String url = bankServiceUrl + "/api/bank/account/" + accountNumber + "/balance";
        try {
            ResponseEntity<java.util.Map> resp = restTemplate.getForEntity(url, java.util.Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object bal = resp.getBody().get("balance");
                if (bal != null) {
                    return new java.math.BigDecimal(String.valueOf(bal));
                }
            }
        } catch (Exception e) {
            logger.error("Get balance failed for account {}: {}", accountNumber, e.getMessage());
        }
        return null;
    }
    
    /**
     * Transfer interface - with retry mechanism
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
     * Refund interface - with retry mechanism
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

    /**
     * Create bank account for user, returns account number
     * Expected bank service endpoint: POST /api/bank/account  body:{"ownerEmail":"...","initialBalance":10000}
     */
    public String createCustomerAccount(String ownerEmail, java.math.BigDecimal initialBalance) {
        String url = bankServiceUrl + "/api/bank/account";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("ownerEmail", ownerEmail);
            body.put("initialBalance", initialBalance);
            HttpEntity<java.util.Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<java.util.Map> resp = restTemplate.postForEntity(url, entity, java.util.Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object acct = resp.getBody().get("accountNumber");
                if (acct != null) return String.valueOf(acct);
            }
        } catch (Exception e) {
            logger.warn("Create bank account failed for {}: {}", ownerEmail, e.getMessage());
        }
        return null;
    }
}


