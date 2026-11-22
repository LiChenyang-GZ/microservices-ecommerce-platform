package comp5348.storeservice.exception;

/**
 * Asked for another user's order list. 403: the caller typed the userId themselves, so saying "not yours"
 * reveals nothing new (ADR-24).
 */
public class NotYourOrdersException extends RuntimeException {
    public NotYourOrdersException(Long userId) {
        super("Cannot read the orders of user " + userId);
    }
}
