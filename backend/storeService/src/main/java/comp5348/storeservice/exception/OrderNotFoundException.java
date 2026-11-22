package comp5348.storeservice.exception;

/**
 * The order does not exist, or it is not the caller's. Both answer 404: order ids are sequential, and a 403
 * would tell a stranger "this id has an order, just not yours" (ADR-24).
 */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long orderId) {
        super("Order not found: " + orderId);
    }
}
