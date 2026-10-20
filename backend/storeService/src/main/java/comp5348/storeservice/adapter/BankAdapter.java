package comp5348.storeservice.adapter;

import com.comp5348.contracts.bank.v1.BankServiceGrpc;
import com.comp5348.contracts.bank.v1.Outcome;
import com.comp5348.contracts.bank.v1.RefundRequest;
import com.comp5348.contracts.bank.v1.RefundResponse;
import com.comp5348.contracts.bank.v1.TransferRequest;
import com.comp5348.contracts.bank.v1.TransferResponse;
import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankTransferRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * store's only way to bank.
 * - transfer / refund (money): gRPC, contracts/bank.proto (ADR-10). Only OutboxProcessor calls these (rule 1).
 * - create account, account by email: still REST; bank.proto has no such calls.
 */
@Component
public class BankAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BankAdapter.class);

    private final RestTemplate restTemplate;
    private final ManagedChannel channel;
    private final BankServiceGrpc.BankServiceBlockingStub bank;
    private final long deadlineMs;

    @Value("${bank.service.url:http://localhost:8084}")
    private String bankServiceUrl;

    public BankAdapter(RestTemplateBuilder builder,
                       @Value("${bank.client.connect-timeout-ms:5000}") int connectTimeoutMs,
                       @Value("${bank.client.read-timeout-ms:5000}") int readTimeoutMs,
                       @Value("${bank.grpc.address:localhost:9090}") String grpcAddress,
                       @Value("${bank.grpc.deadline-ms:5000}") long deadlineMs) {
        this.restTemplate = builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    return factory;
                })
                .build();
        // One long-lived HTTP/2 connection to bank, shared by every call.
        // usePlaintext: no TLS inside the compose / k8s network (same as the REST calls).
        // disableRetry: OutboxProcessor is the only place that retries (5 fast, then every 10 minutes);
        //   a second retry layer here would multiply the calls and the time per task.
        this.channel = NettyChannelBuilder.forTarget(grpcAddress).usePlaintext().disableRetry().build();
        this.bank = BankServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
    }

    @PreDestroy
    void closeChannel() {
        channel.shutdownNow();
    }

    /**
     * Settings for ONE call, made fresh every time.
     * - withDeadlineAfter: "5 seconds from now". Set once on the stub at startup, it would mean "5 seconds after
     *   startup" and every later call would fail at once (failure table row 13).
     * - withWaitForReady: if the connection is down (bank restarting), wait for it until the deadline instead of
     *   failing at once. Without it, gRPC's reconnect backoff can burn all 5 fast retries in 2 seconds (row 16).
     */
    private BankServiceGrpc.BankServiceBlockingStub oneCall() {
        return bank.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).withWaitForReady();
    }

    /**
     * One transfer call. No retry here: OutboxProcessor decides whether and when to retry (same key).
     * Only OutboxProcessor may call this (rule 1, checked by BankAccessArchitectureTest).
     */
    public BankCallResult transfer(BankTransferRequest request) {
        logger.info("Calling bank transfer (gRPC): key={}, amount={}", request.getIdempotencyKey(), request.getAmount());
        BankCallResult result;
        try {
            TransferResponse response = oneCall().transfer(TransferRequest.newBuilder()
                    .setIdempotencyKey(nullToEmpty(request.getIdempotencyKey()))
                    .setFromAccount(nullToEmpty(request.getFromAccount()))
                    .setToAccount(nullToEmpty(request.getToAccount()))
                    .setAmount(decimal(request.getAmount()))
                    .build());
            result = fromOutcome(response.getOutcome(), response.getTransactionId(), response.getReason());
        } catch (StatusRuntimeException e) {
            result = unknown(e);
        }
        logger.info("Bank transfer result: key={}, outcome={}, txnId={}, message={}",
                request.getIdempotencyKey(), result.outcome(), result.transactionId(), result.message());
        return result;
    }

    /**
     * One refund call. Same rules as transfer.
     */
    public BankCallResult refund(BankRefundRequest request) {
        logger.info("Calling bank refund (gRPC): key={}, originalTransactionId={}",
                request.getIdempotencyKey(), request.getOriginalTransactionId());
        BankCallResult result;
        try {
            RefundResponse response = oneCall().refund(RefundRequest.newBuilder()
                    .setIdempotencyKey(nullToEmpty(request.getIdempotencyKey()))
                    .setOriginalTransactionId(nullToEmpty(request.getOriginalTransactionId()))
                    .setReason(nullToEmpty(request.getReason()))
                    .build());
            result = fromOutcome(response.getOutcome(), response.getRefundTransactionId(), response.getReason());
        } catch (StatusRuntimeException e) {
            result = unknown(e);
        }
        logger.info("Bank refund result: key={}, outcome={}, refundTxnId={}, message={}",
                request.getIdempotencyKey(), result.outcome(), result.transactionId(), result.message());
        return result;
    }

    /**
     * Status OK: only an explicit SUCCEEDED or REJECTED is bank's answer.
     * OUTCOME_UNSPECIFIED (bank left the field out) or a value this store does not know → UNKNOWN, never REJECTED
     * (failure table row 11): REJECTED would mark the payment FAILED and let Pay Now charge again with a new key.
     */
    private static BankCallResult fromOutcome(Outcome outcome, String transactionId, String reason) {
        return switch (outcome) {
            // proto3 has no null: "" means "no transaction id". Keep it null, as the HTTP version did (row 12)
            case SUCCEEDED -> BankCallResult.succeeded(transactionId.isEmpty() ? null : transactionId, null);
            case REJECTED -> BankCallResult.rejected(reason);
            default -> BankCallResult.unknown("bank answered OK without an outcome (" + outcome + ")");
        };
    }

    /**
     * Any gRPC error status → UNKNOWN (bank.proto). DEADLINE_EXCEEDED and UNAVAILABLE may come after bank already
     * moved the money; even codes that sound definite are never a failure. Retrying the same key is always safe.
     */
    private static BankCallResult unknown(StatusRuntimeException e) {
        Status status = e.getStatus();
        return BankCallResult.unknown("gRPC " + status.getCode() + ": " + status.getDescription());
    }

    /** Decimal string for bank.proto: toPlainString gives "100", never "1E+2"; the scale stays ("129.90"). */
    private static String decimal(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    // protobuf setters throw on null. "" reaches bank, which rejects it like the HTTP version rejected null (row 20)
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
