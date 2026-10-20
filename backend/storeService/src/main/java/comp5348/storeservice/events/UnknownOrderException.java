package comp5348.storeservice.events;

/**
 * An event names an order store does not have. Retrying cannot make the order appear,
 * so the message goes to the DLT (rule 6: "the referenced order does not exist").
 */
public class UnknownOrderException extends RuntimeException {
    public UnknownOrderException(long orderId) {
        super("Order " + orderId + " does not exist in store");
    }
}
