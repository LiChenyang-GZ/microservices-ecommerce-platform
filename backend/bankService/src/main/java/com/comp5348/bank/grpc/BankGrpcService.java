package com.comp5348.bank.grpc;

import com.comp5348.bank.dto.RefundRequest;
import com.comp5348.bank.dto.RefundResponse;
import com.comp5348.bank.dto.TransferRequest;
import com.comp5348.bank.dto.TransferResponse;
import com.comp5348.bank.service.BankService;
import com.comp5348.contracts.bank.v1.BankServiceGrpc;
import com.comp5348.contracts.bank.v1.Outcome;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The gRPC side of bank, for store's OutboxProcessor (ADR-10). REST (BankController) stays for the frontend.
 * Both call the same BankService, so idempotency and locking are identical.
 *
 * How answers are encoded (contracts/bank.proto):
 * - bank's own answer, yes or no → status OK + outcome SUCCEEDED / REJECTED.
 * - anything else (an exception) → a gRPC error status. Store reads every error status as "unknown" and retries
 *   with the same key. A business "no" must never be an error status (store would retry it forever), and an
 *   exception must never be REJECTED (store would mark the payment FAILED and Pay Now would charge with a new key).
 */
@Component
public class BankGrpcService extends BankServiceGrpc.BankServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(BankGrpcService.class);

    private final BankService bankService;

    public BankGrpcService(BankService bankService) {
        this.bankService = bankService;
    }

    @Override
    public void transfer(com.comp5348.contracts.bank.v1.TransferRequest request,
                         StreamObserver<com.comp5348.contracts.bank.v1.TransferResponse> responseObserver) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(request.getAmount());
        } catch (NumberFormatException e) {
            // Nothing moved and nothing can: a definite "no", not an error status
            reply(responseObserver, rejectedTransfer("Invalid amount: '" + request.getAmount() + "'"));
            return;
        }
        TransferResponse result;
        try {
            result = bankService.transfer(new TransferRequest(
                    request.getIdempotencyKey(), request.getFromAccount(), request.getToAccount(), amount));
        } catch (RuntimeException e) {
            failInternally(responseObserver, "transfer", request.getIdempotencyKey(), e);
            return;
        }
        reply(responseObserver, com.comp5348.contracts.bank.v1.TransferResponse.newBuilder()
                .setOutcome(result.isSuccess() ? Outcome.SUCCEEDED : Outcome.REJECTED)
                .setTransactionId(result.isSuccess() ? nullToEmpty(result.getTransactionId()) : "")
                .setReason(result.isSuccess() ? "" : nullToEmpty(result.getMessage()))
                .build());
    }

    @Override
    public void refund(com.comp5348.contracts.bank.v1.RefundRequest request,
                       StreamObserver<com.comp5348.contracts.bank.v1.RefundResponse> responseObserver) {
        RefundResponse result;
        try {
            result = bankService.refund(new RefundRequest(
                    request.getIdempotencyKey(), request.getOriginalTransactionId(), request.getReason()));
        } catch (RuntimeException e) {
            failInternally(responseObserver, "refund", request.getIdempotencyKey(), e);
            return;
        }
        reply(responseObserver, com.comp5348.contracts.bank.v1.RefundResponse.newBuilder()
                .setOutcome(result.isSuccess() ? Outcome.SUCCEEDED : Outcome.REJECTED)
                .setRefundTransactionId(result.isSuccess() ? nullToEmpty(result.getRefundTransactionId()) : "")
                .setReason(result.isSuccess() ? "" : nullToEmpty(result.getMessage()))
                .build());
    }

    private static com.comp5348.contracts.bank.v1.TransferResponse rejectedTransfer(String reason) {
        return com.comp5348.contracts.bank.v1.TransferResponse.newBuilder()
                .setOutcome(Outcome.REJECTED).setReason(reason).build();
    }

    /**
     * Exceptions become INTERNAL. The details stay in bank's log; the caller only learns "bank failed",
     * not table names or SQL from the exception message.
     */
    private static void failInternally(StreamObserver<?> responseObserver, String operation, String key, RuntimeException e) {
        logger.error("gRPC {} failed: key={}", operation, key, e);
        responseObserver.onError(Status.INTERNAL.withDescription("bank internal error").asRuntimeException());
    }

    /**
     * Send the answer. If the caller already gave up (deadline passed), the money operation above has still
     * committed; gRPC then refuses the late answer, which is fine: the caller retries with the same key
     * and gets this same result back.
     */
    private static <T> void reply(StreamObserver<T> responseObserver, T response) {
        try {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException callerGone) {
            logger.info("gRPC caller gone before the answer was sent: {}", callerGone.getMessage());
        }
    }

    // protobuf setters reject null; an absent value is the empty string in proto3
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
