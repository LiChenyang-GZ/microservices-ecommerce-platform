package comp5348.storeservice.config;

/**
 * store is the caller: it holds one service token per callee (bank, delivery, email), each read by the class that
 * calls that service (BankAdapter, DeliveryAdapter, AccountService). Each of them checks its own token here.
 *
 * Blank or missing → store refuses to start. A store that starts without one looks healthy, then every payment,
 * cancel or password reset is refused; the ALERT only shows up at the first such call. Failing at startup shows
 * the mistake the moment it is deployed, and names the missing key.
 */
public final class ServiceTokens {

    /** HTTP header for REST calls; the gRPC metadata key is the same name in lower case (x-service-token) */
    public static final String HEADER = "X-Service-Token";

    private ServiceTokens() {
    }

    /**
     * @param property the config key, e.g. bank.service-token
     * @param envVar   the env var deployments fill it from, e.g. BANK_SERVICE_TOKEN
     * @param callee   who the token is for, for the message
     * @return the token, unchanged
     */
    public static String require(String property, String envVar, String callee, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(property + " is not set (env var " + envVar + "); "
                    + "store will not start without the service token it needs to call " + callee);
        }
        return token;
    }
}
