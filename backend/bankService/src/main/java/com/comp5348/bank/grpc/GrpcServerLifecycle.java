package com.comp5348.bank.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;

/**
 * Starts bank's gRPC server next to the REST server (port BANK_GRPC_PORT, default 9090).
 *
 * SmartLifecycle: Spring calls start() after every bean (BankService, the database pool) is ready,
 * and stop() before those beans are destroyed, so a call in flight can still reach the database.
 * If the port is taken, start() throws and bank fails to start: loud, not a bank that silently has no gRPC.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final BankGrpcService bankGrpcService;
    private final int port;
    private volatile Server server;

    public GrpcServerLifecycle(BankGrpcService bankGrpcService, @Value("${bank.grpc.port:9090}") int port) {
        this.bankGrpcService = bankGrpcService;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forPort(port).addService(bankGrpcService).build().start();
        } catch (IOException e) {
            throw new UncheckedIOException("bank gRPC server could not listen on port " + port, e);
        }
        logger.info("bank gRPC server listening on port {}", server.getPort());
    }

    /** Stop taking new calls, give calls in flight up to 5 seconds, then cut the rest. */
    @Override
    public void stop() {
        Server current = server;
        if (current == null) return;
        current.shutdown();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        server = null;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }
}
