package comp5348.storeservice.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address a rate limit counts by (Obsidian 54, R16).
 *
 * Requests from outside come through one entry: nginx in compose, Envoy in Kubernetes. Each puts the address it
 * saw at the END of X-Forwarded-For: nginx replaces the header with just $remote_addr, Envoy appends to whatever
 * the client sent. So only the last value is ours; anything before it was written by the client and is ignored.
 * No header (a script calling store's port directly, or another service): the connection's own address.
 *
 * Someone who reaches store without the entry can write any X-Forwarded-For. Only this machine (compose publishes
 * on 127.0.0.1) and pods inside the cluster can do that.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            int comma = forwarded.lastIndexOf(',');
            String last = forwarded.substring(comma + 1).trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
