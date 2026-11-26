package comp5348.storeservice.utils;

import java.util.Locale;

/**
 * One spelling per mailbox (Obsidian 74): " Victim@X.com" and "victim@x.com" reach one inbox, so store keeps one
 * account for them. Before this, the unique column (case-sensitive in Postgres) let them be two accounts, and someone
 * whose phone capitalised the first letter could not log in or reset their password.
 *
 * Applied at two choke points, so a new caller cannot forget it: Account#setEmail (every write) and
 * AccountRepository#findByEmail (every read). AccountService also passes the normalised address on to email and bank,
 * because email keeps codes by address.
 *
 * Locale.ROOT, not the server's language: in Turkish "I".toLowerCase() is a dotless "ı". AuthRateLimits counts by the
 * same spelling.
 * The part before the @ may be case-sensitive by RFC 5321; no mainstream provider treats it so. Known limit.
 */
public final class EmailAddress {

    private EmailAddress() {
    }

    /** Trimmed and lower-cased; null stays null, so the usual "valid email" check still rejects it */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
