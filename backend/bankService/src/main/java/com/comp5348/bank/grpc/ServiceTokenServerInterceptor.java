package com.comp5348.bank.grpc;

import com.comp5348.bank.config.ServiceToken;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Runs before every gRPC method: no x-service-token in the metadata, or the wrong one → UNAUTHENTICATED,
 * and the call is closed before BankGrpcService (so before any money moves).
 *
 * gRPC does not go through servlet filters, which is why bank needs this as well as ServiceTokenFilter.
 * Store maps UNAUTHENTICATED to UNKNOWN and logs ALERT bank_auth_failed (ADR-22): a wrong token must not
 * look like a business "no".
 */
public class ServiceTokenServerInterceptor implements ServerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ServiceTokenServerInterceptor.class);

    /** gRPC metadata keys must be lower case. */
    static final Metadata.Key<String> TOKEN_KEY =
            Metadata.Key.of(ServiceToken.HEADER.toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER);

    private final ServiceToken serviceToken;

    public ServiceTokenServerInterceptor(ServiceToken serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String presented = headers.get(TOKEN_KEY);
        if (!serviceToken.matches(presented)) {
            logger.warn("Rejected gRPC {}: {} service token",
                    call.getMethodDescriptor().getFullMethodName(), presented == null ? "no" : "wrong");
            call.close(Status.UNAUTHENTICATED.withDescription("service token required"), new Metadata());
            return new ServerCall.Listener<>() { };
        }
        return next.startCall(call, headers);
    }
}
