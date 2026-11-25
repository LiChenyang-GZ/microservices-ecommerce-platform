package comp5348.storeservice.adapter;

/**
 * What one call to bank told us.
 *
 * SUCCEEDED / REJECTED are bank's own answers (contracts/bank.proto Outcome).
 * UNKNOWN means we got no answer we can trust (timeout, connection reset, 5xx): the money may or may not
 * have moved, so the caller must retry with the same key and must never treat it as a failure (rule 7).
 * KEY_CONFLICT is bank's answer "this key was already used for a different request": nothing moved for this call,
 * but the key's first request may have moved money, so it is no failure either (ADR-29). Resending the same
 * request gets the same answer, so the caller stops the round instead of retrying at once.
 */
public record BankCallResult(Outcome outcome, String transactionId, String message) {

    public enum Outcome { SUCCEEDED, REJECTED, UNKNOWN, KEY_CONFLICT }

    static BankCallResult succeeded(String transactionId, String message) {
        return new BankCallResult(Outcome.SUCCEEDED, transactionId, message);
    }

    static BankCallResult rejected(String message) {
        return new BankCallResult(Outcome.REJECTED, null, message);
    }

    static BankCallResult keyConflict(String message) {
        return new BankCallResult(Outcome.KEY_CONFLICT, null, message);
    }

    static BankCallResult unknown(String message) {
        return new BankCallResult(Outcome.UNKNOWN, null, message);
    }
}
