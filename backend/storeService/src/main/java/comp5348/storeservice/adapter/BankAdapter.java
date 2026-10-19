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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Component
public class BankAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BankAdapter.class);

    private final RestTemplate restTemplate;

    @Value("${bank.service.url:http://localhost:8084}")
    private String bankServiceUrl;

    public BankAdapter(RestTemplateBuilder builder,
                       @Value("${bank.client.connect-timeout-ms:5000}") int connectTimeoutMs,
                       @Value("${bank.client.read-timeout-ms:5000}") int readTimeoutMs) {
        this.restTemplate = builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
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
     * One transfer call. No retry here: OutboxProcessor decides whether and when to retry (same key).
     * Only OutboxProcessor may call this (rule 1, checked by BankAccessArchitectureTest).
     */
    public BankCallResult transfer(BankTransferRequest request) {
        logger.info("Calling bank transfer: key={}, amount={}", request.getIdempotencyKey(), request.getAmount());
        BankCallResult result = post("/api/bank/transfer", request, BankTransferResponse.class);
        logger.info("Bank transfer result: key={}, outcome={}, txnId={}, message={}",
                request.getIdempotencyKey(), result.outcome(), result.transactionId(), result.message());
        return result;
    }

    /**
     * One refund call. Same rules as transfer.
     */
    public BankCallResult refund(BankRefundRequest request) {
        logger.info("Calling bank refund: key={}, originalTransactionId={}",
                request.getIdempotencyKey(), request.getOriginalTransactionId());
        BankCallResult result = post("/api/bank/refund", request, BankRefundResponse.class);
        logger.info("Bank refund result: key={}, outcome={}, refundTxnId={}, message={}",
                request.getIdempotencyKey(), result.outcome(), result.transactionId(), result.message());
        return result;
    }

    /**
     * Turn one HTTP call into SUCCEEDED / REJECTED / UNKNOWN.
     * - 2xx with success=true          → SUCCEEDED
     * - bank's own "no" (success=false) → REJECTED. Bank sends it as HTTP 400 with a JSON body.
     * - anything else: timeout, connection reset, 5xx, a 4xx without bank's JSON → UNKNOWN.
     *   We cannot tell whether the money moved, so this is never a failure.
     */
    private <T> BankCallResult post(String path, Object request, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<T> response = restTemplate.postForEntity(
                    bankServiceUrl + path, new HttpEntity<>(request, headers), responseType);
            return fromBody(response.getBody());
        } catch (HttpClientErrorException e) {
            try {
                T body = e.getResponseBodyAs(responseType);
                BankCallResult result = fromBody(body);
                if (result.outcome() == BankCallResult.Outcome.REJECTED) {
                    return result;
                }
            } catch (Exception parseError) {
                // not bank's JSON: fall through to UNKNOWN
            }
            return BankCallResult.unknown("HTTP " + e.getStatusCode().value() + " without a bank answer");
        } catch (RestClientException e) {
            return BankCallResult.unknown(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static BankCallResult fromBody(Object body) {
        if (body instanceof BankTransferResponse t) {
            return t.isSuccess() ? BankCallResult.succeeded(t.getTransactionId(), t.getMessage())
                    : BankCallResult.rejected(t.getMessage());
        }
        if (body instanceof BankRefundResponse r) {
            return r.isSuccess() ? BankCallResult.succeeded(r.getRefundTransactionId(), r.getMessage())
                    : BankCallResult.rejected(r.getMessage());
        }
        return BankCallResult.unknown("Empty response from bank");
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

    /**
     * Get account by owner email (for inter-service communication)
     * Returns account number if found, null otherwise
     */
    public String getAccountByOwnerEmail(String ownerEmail) {
        String url = bankServiceUrl + "/api/bank/account/by-owner/" + ownerEmail;
        try {
            ResponseEntity<java.util.Map> resp = restTemplate.getForEntity(url, java.util.Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object acct = resp.getBody().get("accountNumber");
                if (acct != null) {
                    logger.info("Found bank account for {}: {}", ownerEmail, acct);
                    return String.valueOf(acct);
                }
            }
        } catch (Exception e) {
            logger.warn("Get bank account by email failed for {}: {}", ownerEmail, e.getMessage());
        }
        return null;
    }
}
