package comp5348.storeservice.adapter;

import com.comp5348.contracts.bank.v1.BankServiceGrpc;
import com.comp5348.contracts.bank.v1.Outcome;
import com.comp5348.contracts.bank.v1.RefundRequest;
import com.comp5348.contracts.bank.v1.RefundResponse;
import com.comp5348.contracts.bank.v1.TransferRequest;
import com.comp5348.contracts.bank.v1.TransferResponse;
import comp5348.storeservice.config.ServiceTokens;
import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankTransferRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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

    /** bank's own service token goes in the X-Service-Token header (REST) and this metadata key (gRPC) */
    private static final Metadata.Key<String> SERVICE_TOKEN_KEY =
            Metadata.Key.of("x-service-token", Metadata.ASCII_STRING_MARSHALLER);

    public BankAdapter(RestTemplateBuilder builder,
                       @Value("${bank.client.connect-timeout-ms:5000}") int connectTimeoutMs,
                       @Value("${bank.client.read-timeout-ms:5000}") int readTimeoutMs,
                       @Value("${bank.grpc.address:localhost:9090}") String grpcAddress,
                       @Value("${bank.grpc.deadline-ms:5000}") long deadlineMs,
                       @Value("${bank.service-token:}") String serviceToken) {
        ServiceTokens.require("bank.service-token", "BANK_SERVICE_TOKEN", "bank", serviceToken);
        // defaultHeader: every REST call from this RestTemplate carries bank's token. This RestTemplate only
        // ever talks to bank, so bank's token never reaches another service (each callee has its own token).
        this.restTemplate = builder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    return factory;
                })
                .defaultHeader(ServiceTokens.HEADER, serviceToken)
                .build();
        Metadata token = new Metadata();
        token.put(SERVICE_TOKEN_KEY, serviceToken);
        // One long-lived HTTP/2 connection to bank, shared by every call.
        // usePlaintext: no TLS inside the compose / k8s network (same as the REST calls).
        // disableRetry: OutboxProcessor is the only place that retries (5 fast, then every 10 minutes);
        //   a second retry layer here would multiply the calls and the time per task.
        // intercept: adds x-service-token to the metadata of every call on this channel (transfer and refund).
        this.channel = NettyChannelBuilder.forTarget(grpcAddress).usePlaintext().disableRetry()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(token))
                .build();
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
            alertIfAuthFailed(e, request.getIdempotencyKey());
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
            alertIfAuthFailed(e, request.getIdempotencyKey());
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

    /**
     * UNAUTHENTICATED = bank refused store's service token (wrong or missing in store's config). The result stays
     * UNKNOWN like every other error (it is our fault, not the buyer's: no FAILED, no new key), but unlike a timeout
     * it will not fix itself, so it gets its own ALERT. Only this code: a bank restart (UNAVAILABLE) must not alert.
     */
    private static void alertIfAuthFailed(StatusRuntimeException e, String idempotencyKey) {
        if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
            logger.error("ALERT bank_auth_failed key={} (bank refused store's service token; check bank.service-token)",
                    idempotencyKey);
        }
    }

    /**
     * The REST half of the same alert (scenario C3b): bank answered 401 to open-account or account-by-owner.
     * The caller still gets null, as before. Without this line the only trace is a WARN "create bank account
     * failed", and the user later sees "no linked bank account" at Pay Now. Names the operation, never the email:
     * alert lines are forwarded to other systems, the email is personal data.
     */
    private static void alertRestAuthFailed(String operation) {
        logger.error("ALERT bank_auth_failed op={} (bank refused store's service token; check bank.service-token)",
                operation);
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
        } catch (HttpClientErrorException.Unauthorized e) {
            alertRestAuthFailed("create-account");
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
        // The email is one path segment, encoded ("/" → %2F). Pasted in raw, "x/../victim@…" became
        // /by-owner/x/../victim@…, which bank's Tomcat normalises to the victim's lookup (scenario A9).
        // A URI, not a String: RestTemplate would encode a String again (%2F → %252F).
        java.net.URI url = org.springframework.web.util.UriComponentsBuilder.fromUriString(bankServiceUrl)
                .pathSegment("api", "bank", "account", "by-owner", ownerEmail)
                .encode()
                .build()
                .toUri();
        try {
            ResponseEntity<java.util.Map> resp = restTemplate.getForEntity(url, java.util.Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Object acct = resp.getBody().get("accountNumber");
                if (acct != null) {
                    logger.info("Found bank account for {}: {}", ownerEmail, acct);
                    return String.valueOf(acct);
                }
            }
        } catch (HttpClientErrorException.Unauthorized e) {
            alertRestAuthFailed("account-by-owner");
        } catch (Exception e) {
            logger.warn("Get bank account by email failed for {}: {}", ownerEmail, e.getMessage());
        }
        return null;
    }
}
