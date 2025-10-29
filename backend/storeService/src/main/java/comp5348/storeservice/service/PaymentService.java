package comp5348.storeservice.service;

import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankRefundResponse;
import comp5348.storeservice.dto.BankTransferRequest;
import comp5348.storeservice.dto.BankTransferResponse;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private BankAdapter bankAdapter;
    
    @Autowired
    private OutboxService outboxService;
    
    @Value("${bank.store.account:STORE_ACCOUNT_001}")
    private String storeAccount;
    
    @Value("${bank.customer.account:CUSTOMER_ACCOUNT_001}")
    private String customerAccount;
    
    /**
     * 创建支付记录 - 幂等性保证
     */
    @Transactional
    public Payment createPayment(Long orderId, BigDecimal amount) {
        logger.info("Creating payment for orderId={}, amount={}", orderId, amount);
        
        // 幂等性检查
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);
        if (existingPayment.isPresent()) {
            logger.info("Payment already exists for orderId={}", orderId);
            return existingPayment.get();
        }
        
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(amount);
        payment.setStatus(PaymentStatus.PENDING);
        
        Payment savedPayment = paymentRepository.save(payment);
        logger.info("Payment created: id={}, orderId={}", savedPayment.getId(), orderId);
        
        // 创建待支付事件到Outbox
        outboxService.createPaymentPendingEvent(orderId, amount);
        
        return savedPayment;
    }
    
    /**
     * 处理支付 - 调用Bank服务
     */
    @Transactional
    public void processPayment(Long orderId) {
        logger.info("Processing payment for orderId={}", orderId);
        
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        if (!paymentOpt.isPresent()) {
            logger.error("Payment not found for orderId={}", orderId);
            return;
        }
        
        Payment payment = paymentOpt.get();
        
        // 如果已经处理过，不重复处理
        if (payment.getStatus() != PaymentStatus.PENDING) {
            logger.info("Payment already processed: orderId={}, status={}", orderId, payment.getStatus());
            return;
        }
        
        // 生成唯一的交易参考号
        String transactionRef = "TXN-" + orderId + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        // 调用Bank服务转账
        BankTransferRequest transferRequest = new BankTransferRequest(
                customerAccount,
                storeAccount,
                payment.getAmount(),
                transactionRef
        );
        
        BankTransferResponse response = bankAdapter.transfer(transferRequest);
        
        if (response.isSuccess()) {
            handlePaymentSuccess(payment, response.getTransactionId());
        } else {
            handlePaymentFailure(payment, response.getMessage());
        }
    }
    
    /**
     * 处理支付成功
     */
    @Transactional
    public void handlePaymentSuccess(Payment payment, String bankTxnId) {
        logger.info("Payment successful: orderId={}, bankTxnId={}", payment.getOrderId(), bankTxnId);
        
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setBankTxnId(bankTxnId);
        paymentRepository.save(payment);
        
        // 创建支付成功事件到Outbox（用于触发配送）
        outboxService.createPaymentSuccessEvent(payment.getOrderId(), bankTxnId);
        
        logger.info("Payment success event created for orderId={}", payment.getOrderId());
    }
    
    /**
     * 处理支付失败
     */
    @Transactional
    public void handlePaymentFailure(Payment payment, String errorMessage) {
        logger.error("Payment failed: orderId={}, error={}", payment.getOrderId(), errorMessage);
        
        payment.setStatus(PaymentStatus.FAILED);
        payment.setErrorMessage(errorMessage);
        paymentRepository.save(payment);
        
        // 创建支付失败事件到Outbox（用于释放库存和发送通知）
        outboxService.createPaymentFailedEvent(payment.getOrderId(), errorMessage);
        
        logger.info("Payment failure event created for orderId={}", payment.getOrderId());
    }
    
    /**
     * 退款处理
     */
    @Transactional
    public Payment refundPayment(Long orderId, String reason) {
        logger.info("Processing refund for orderId={}, reason={}", orderId, reason);
        
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        if (!paymentOpt.isPresent()) {
            throw new IllegalArgumentException("Payment not found for orderId: " + orderId);
        }
        
        Payment payment = paymentOpt.get();
        
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Can only refund successful payments. Current status: " + payment.getStatus());
        }
        
        // 调用Bank服务退款
        BankRefundRequest refundRequest = new BankRefundRequest(payment.getBankTxnId(), reason);
        BankRefundResponse response = bankAdapter.refund(refundRequest);
        
        if (response.isSuccess()) {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setErrorMessage(reason);
            paymentRepository.save(payment);
            
            logger.info("Refund successful: orderId={}, refundTxnId={}", orderId, response.getRefundTransactionId());
            
            // 创建退款成功事件（用于发送邮件通知）
            outboxService.createRefundSuccessEvent(orderId, response.getRefundTransactionId());
        } else {
            logger.error("Refund failed: orderId={}, error={}", orderId, response.getMessage());
            throw new RuntimeException("Refund failed: " + response.getMessage());
        }
        
        return payment;
    }
    
    /**
     * 根据orderId查询支付
     */
    public Optional<Payment> getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
    
    /**
     * 根据id查询支付
     */
    public Optional<Payment> getPaymentById(Long id) {
        return paymentRepository.findById(id);
    }
}


