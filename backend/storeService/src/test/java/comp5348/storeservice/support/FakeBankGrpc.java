package comp5348.storeservice.support;

import com.comp5348.contracts.bank.v1.BankServiceGrpc;
import com.comp5348.contracts.bank.v1.RefundRequest;
import com.comp5348.contracts.bank.v1.RefundResponse;
import com.comp5348.contracts.bank.v1.TransferRequest;
import com.comp5348.contracts.bank.v1.TransferResponse;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 假 bank，说 gRPC。store 的集成测试用它代替真 bank（以前是 WireMock 冒充 HTTP 的 bank）。
 *
 * 【它是什么】一个真的 gRPC 服务器（Netty，监听本机一个端口），实现 bank.proto 里的 BankService。
 *   store 的 BankAdapter 通过真的网络连它，所以超时（deadline）、连接断开、重连这些都是真的。
 * 【它怎么回答】按"剧本"：测试先说好"第 1 次慢 6 秒回成功、第 2 次回 UNAVAILABLE……"，
 *   收到第 n 个请求就用剧本里第 n 条回答；剧本用完了，一直用最后一条。
 * 【它记下什么】收到的每个请求（在回答之前就记下），测试拿来数"bank 收到了几次、用的什么键"。
 */
public class FakeBankGrpc extends BankServiceGrpc.BankServiceImplBase {

    /**
     * 一条回答：先等 delay，再回 status（不是 OK 就是 gRPC 错误），status 是 OK 时回 response。
     * response 用 Object：扣款剧本里放 TransferResponse，退款剧本里放 RefundResponse。
     */
    public record Reply(Duration delay, Status status, Object response) {
        public static Reply ok(Object response) {
            return new Reply(Duration.ZERO, Status.OK, response);
        }
        public static Reply okAfter(Duration delay, Object response) {
            return new Reply(delay, Status.OK, response);
        }
        public static Reply error(Status.Code code) {
            return new Reply(Duration.ZERO, Status.fromCode(code).withDescription("scripted by test"), null);
        }
    }

    private final int port;
    private volatile Server server;

    // volatile + 整个替换：测试线程换剧本，gRPC 的处理线程读剧本，不会读到一半
    private volatile List<Reply> transferScript = List.of();
    private volatile List<Reply> refundScript = List.of();
    // 线程安全的列表：gRPC 在自己的线程里往里加，测试线程读
    private final List<TransferRequest> transfers = new CopyOnWriteArrayList<>();
    private final List<RefundRequest> refunds = new CopyOnWriteArrayList<>();

    public FakeBankGrpc(int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    public void start() {
        try {
            server = NettyServerBuilder.forPort(port).addService(this).build().start();
        } catch (IOException e) {
            throw new IllegalStateException("fake bank could not listen on " + port, e);
        }
    }

    /** 立刻停掉，断开所有连接（模拟 bank 进程被杀） */
    public void stop() {
        Server current = server;
        if (current == null) return;
        current.shutdownNow();
        try {
            current.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 清掉剧本和请求记录。没写剧本时，每个请求都回 UNIMPLEMENTED（对 store 来说就是"不知道"）。 */
    public void reset() {
        transferScript = List.of();
        refundScript = List.of();
        forgetRequests();
    }

    public void forgetRequests() {
        transfers.clear();
        refunds.clear();
    }

    public void onTransfer(Reply... replies) {
        transferScript = List.of(replies);
    }

    public void onRefund(Reply... replies) {
        refundScript = List.of(replies);
    }

    public List<TransferRequest> transfers() {
        return List.copyOf(transfers);
    }

    public List<RefundRequest> refunds() {
        return List.copyOf(refunds);
    }

    // ---------- gRPC 调进来的两个方法 ----------

    @Override
    public void transfer(TransferRequest request, StreamObserver<TransferResponse> responseObserver) {
        transfers.add(request);
        answer(pick(transferScript, transfers.size()), responseObserver);
    }

    @Override
    public void refund(RefundRequest request, StreamObserver<RefundResponse> responseObserver) {
        refunds.add(request);
        answer(pick(refundScript, refunds.size()), responseObserver);
    }

    /** 第 n 个请求（从 1 数）用剧本第 n 条；剧本短了就用最后一条；没有剧本就 UNIMPLEMENTED */
    private static Reply pick(List<Reply> script, int n) {
        if (script.isEmpty()) return Reply.error(Status.Code.UNIMPLEMENTED);
        return script.get(Math.min(n, script.size()) - 1);
    }

    @SuppressWarnings("unchecked")
    private static <T> void answer(Reply reply, StreamObserver<T> responseObserver) {
        try {
            if (!reply.delay().isZero()) {
                Thread.sleep(reply.delay().toMillis());
            }
            if (reply.status().isOk()) {
                responseObserver.onNext((T) reply.response());
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(reply.status().asRuntimeException());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException clientAlreadyGone) {
            // 慢回答的时候，store 可能早就因为超时放弃了；这时再回答会报"调用已取消"，忽略即可
        }
    }
}
