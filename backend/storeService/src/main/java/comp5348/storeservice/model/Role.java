package comp5348.storeservice.model;

/**
 * Who a logged-in user is to store (auth PR 3, decision 2). Carried in the JWT's "role" claim and in validate-token's
 * reply. Anything missing or unknown counts as USER, never ADMIN: a token signed before roles existed has no claim.
 */
public enum Role {
    USER,
    ADMIN;

    /** "ADMIN" → ADMIN; null, "USER" or anything else → USER */
    public static Role of(Object value) {
        return value != null && ADMIN.name().equals(value.toString()) ? ADMIN : USER;
    }
}
