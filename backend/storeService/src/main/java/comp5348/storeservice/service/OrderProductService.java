package comp5348.storeservice.service;

import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.exception.NotYourOrdersException;
import comp5348.storeservice.exception.OrderNotFoundException;
import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.comp5348.contracts.events.OrderEventType;
import com.comp5348.contracts.events.payload.OrderPlacedPayload;
import comp5348.storeservice.events.OrderEventRecorder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Integrated Order and Product Management Service
 * Integrates product management, order management, and payment functionality
 */
@Service
@Transactional
public class OrderProductService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderProductService.class);
    
    @Autowired
    private ProductService productService;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private BankAdapter bankAdapter;

    @Autowired
    private WarehouseService warehouseService;
    
    @org.springframework.beans.factory.annotation.Value("${bank.customer.account:CUSTOMER_ACCOUNT_001}")
    private String customerAccount;

    @Autowired
    private comp5348.storeservice.repository.AccountRepository accountRepository;

    @Autowired
    private comp5348.storeservice.repository.PaymentRepository paymentRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OrderEventRecorder orderEventRecorder;

    /**
     * Checkout from the HTTP API: the order belongs to the logged-in user, whatever userId the body says
     * (auth PR 2, scenario A5: the body's userId is ignored, not refused, so the frontend needs no change).
     */
    public OrderDTO createOrderWithPaymentFor(Long callerId, CreateOrderRequest request) {
        request.setUserId(accountOf(callerId).getId());
        return createOrderWithPayment(request);
    }

    /**
     * Create order and process payment
     * This is the core method of integration, handling the entire flow from product selection to payment completion
     */
    public OrderDTO createOrderWithPayment(CreateOrderRequest request) {
        logger.info("Creating order with payment for user: {}", request.getUserId());
        
        // 0. Estimate total price
        java.math.BigDecimal estimatedTotal = java.math.BigDecimal.ZERO;
        for (CreateOrderRequest.OrderItemRequest item : request.getOrderItems()) {
            comp5348.storeservice.model.Product p = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + item.getProductId()));
            int qty = (item.getQuantity() == null || item.getQuantity() <= 0) ? 1 : item.getQuantity();
            estimatedTotal = estimatedTotal.add(p.getPrice().multiply(java.math.BigDecimal.valueOf(qty)));
        }

        // 1. Create order (stock will be reserved during order creation)
        OrderDTO order = orderService.createOrder(request);
        
        // 2. Update order status to pending payment
        orderService.updateOrderStatus(order.getId(), OrderStatus.PENDING_PAYMENT);
        
        // 3. Create payment record
        try {
            PaymentRequest paymentRequest = new PaymentRequest();
            paymentRequest.setOrderId(order.getId());
            paymentRequest.setAmount(order.getTotalAmount());
            
            Payment payment = paymentService.createPayment(paymentRequest.getOrderId(), paymentRequest.getAmount());
            logger.info("Payment created for order: {}, paymentId: {}", order.getId(), payment.getId());
            
        } catch (Exception e) {
            logger.error("Failed to create payment for order {}: {}", order.getId(), e.getMessage());
            // If payment creation fails, cancel order
            orderService.cancelOrder(order.getId());
            throw new RuntimeException("Failed to create payment: " + e.getMessage());
        }

        // 4. OrderPlaced, in this same transaction as the order, the stock hold and the PAY task (rule 2)
        orderEventRecorder.record(order.getId(), OrderEventType.ORDER_PLACED, new OrderPlacedPayload(
                order.getUserId(), order.getProductId(), order.getQuantity(), order.getTotalAmount()));

        return order;
    }
    
    /**
     * Get complete order information (including payment status)
     */
    public OrderWithPaymentDTO getOrderWithPaymentInfo(Long orderId, Long callerId) {
        logger.info("Getting order with payment info for order: {}", orderId);

        OrderDTO order = ownOrder(orderId, callerId);
        
        // Get payment information
        Optional<Payment> payment = paymentService.getPaymentByOrderId(orderId);
        
        OrderWithPaymentDTO orderWithPayment = new OrderWithPaymentDTO();
        orderWithPayment.setOrder(order);
        orderWithPayment.setPayment(payment.orElse(null));
        
        return orderWithPayment;
    }
    
    /**
     * Get user's order list (including payment status)
     */
    public List<OrderWithPaymentDTO> getUserOrdersWithPaymentInfo(Long userId, Long callerId) {
        logger.info("Getting user orders with payment info for user: {}", userId);
        if (!accountOf(callerId).getId().equals(userId)) {
            throw new NotYourOrdersException(userId);
        }

        List<OrderDTO> orders = orderService.getOrdersByUserId(userId);
        
        return orders.stream()
                .map(order -> {
                    Optional<Payment> payment = paymentService.getPaymentByOrderId(order.getId());
                    OrderWithPaymentDTO orderWithPayment = new OrderWithPaymentDTO();
                    orderWithPayment.setOrder(order);
                    orderWithPayment.setPayment(payment.orElse(null));
                    return orderWithPayment;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * Get product stock information (using multi-warehouse aggregated inventory)
     */
    public ProductStockDTO getProductStockInfo(Long productId) {
        logger.info("Getting product stock info for product: {}", productId);
        
        ProductDTO product = productService.getProductById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        
        // Use multi-warehouse aggregated inventory
        int totalQuantity = warehouseService.getProductQuantity(productId);
        
        ProductStockDTO stockInfo = new ProductStockDTO();
        stockInfo.setProductId(productId);
        stockInfo.setProductName(product.getName());
        stockInfo.setCurrentStock(totalQuantity);
        stockInfo.setAvailable(totalQuantity > 0);

        return stockInfo;
    }

    /**
     * Pay Now on an unpaid order (rule 8). Never calls bank: it only asks PaymentService to queue a new attempt,
     * and only if the last one definitely failed. The page then shows "payment processing" and polls the status.
     */
    public OrderDTO retryOrderPayment(Long orderId, Long callerId) {
        logger.info("Pay Now for order: {}, user: {}", orderId, callerId);

        // The charge always comes from the order owner's bank account, so only the owner may trigger it
        OrderDTO order = ownOrder(orderId, callerId);
        Account userAcc = accountOf(callerId);

        if (OrderStatus.PAID.name().equals(order.getStatus())) {
            return order;
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            throw new IllegalStateException("Order is not awaiting payment. Current status: " + order.getStatus());
        }

        // OutboxProcessor charges the bank account stored here; look it up once if it is missing (no money moves)
        if (userAcc.getBankAccountNumber() == null || userAcc.getBankAccountNumber().isEmpty()) {
            // The stored spelling, not the token's: a token signed before ADR-31's migration may carry other capitals,
            // and bank (exact match, opens an empty account when none is found) would give this user a new one (review 75 🟡2)
            String bankAccountNumber = bankAdapter.getAccountByOwnerEmail(userAcc.getEmail());
            if (bankAccountNumber == null || bankAccountNumber.isEmpty()) {
                throw new IllegalStateException("User has no linked bank account. It is opened when the account is activated.");
            }
            userAcc.setBankAccountNumber(bankAccountNumber);
            accountRepository.save(userAcc);
        }

        PaymentService.PayNowResult result = paymentService.payNow(orderId);
        logger.info("Pay Now for order {}: {}", orderId, result);
        return orderService.getOrderById(orderId).orElse(order);
    }

    /**
     * The user cancels one of their own orders (POST /api/store/orders/{id}/cancel). Checked before anything
     * else, so a stranger's request never locks the order, asks delivery, or writes a refund.
     * The owner never changes, so checking before the row lock is safe; the status does change, which is why
     * OrderService#cancelOrder re-reads the order once it holds the lock (review 44 🟠1).
     */
    public OrderDTO cancelOwnOrder(Long orderId, Long callerId) {
        ownOrder(orderId, callerId);
        return orderService.cancelOrder(orderId);
    }

    /**
     * The order, if it belongs to this user. Missing and someone else's look the same (404), see
     * OrderNotFoundException. Every user-facing read or change of one order goes through here (auth PR 2).
     */
    public OrderDTO ownOrder(Long orderId, Long callerId) {
        OrderDTO order = orderService.getOrderById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!accountOf(callerId).getId().equals(order.getUserId())) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    /**
     * The logged-in user, by the token's userId (Obsidian 82). Not by the token's email: that is a snapshot from when
     * the token was signed, and on older mixed-case data it could resolve to another account or to none (review 75 🟡3).
     * The token was valid, so a missing account means it was deleted since; the request fails.
     */
    private Account accountOf(Long callerId) {
        return accountRepository.findById(callerId)
                .orElseThrow(() -> new IllegalStateException("No account for the logged-in user"));
    }
}
