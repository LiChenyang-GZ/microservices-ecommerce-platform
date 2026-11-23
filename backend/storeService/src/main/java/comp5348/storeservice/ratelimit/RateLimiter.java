package comp5348.storeservice.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Fixed-window counters in Redis (rate limits, Obsidian 54). Redis, not memory, so every store replica sees the
 * same counts and a restart does not reset them.
 *
 * One Lua script does INCR and, on the first hit, PEXPIRE, atomically. Two separate commands could leave a key
 * with no expiry (store dies between them), and that address would then be blocked forever. The script also
 * repairs a key that has lost its expiry somehow.
 *
 * If Redis fails or is slow (spring.data.redis.timeout), the answer is UNAVAILABLE and the caller lets the request
 * through: the limits are an extra guard, and a Redis outage must not stop people logging in (R19).
 */
@Component
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    private static final DefaultRedisScript<List> HIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    /** What one hit found */
    public record Decision(Outcome outcome, long retryAfterSeconds) {
        static final Decision UNAVAILABLE = new Decision(Outcome.UNAVAILABLE, 0);

        public boolean blocked() {
            return outcome == Outcome.BLOCKED;
        }
    }

    public enum Outcome {
        /** under the limit, counted */
        ALLOWED,
        /** over the limit in this window */
        BLOCKED,
        /** Redis did not answer: not counted, let the request through */
        UNAVAILABLE
    }

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Count one request against key; BLOCKED once more than limit requests fell into the current window. */
    public Decision hit(String key, int limit, Duration window) {
        try {
            List<?> result = redis.execute(HIT, List.of(key), String.valueOf(window.toMillis()));
            long count = ((Number) result.get(0)).longValue();
            long millisLeft = ((Number) result.get(1)).longValue();
            if (count <= limit) {
                return new Decision(Outcome.ALLOWED, 0);
            }
            // Round up: "try again in 0 seconds" would be wrong while the window is still open
            long seconds = Math.max(1, (millisLeft + 999) / 1000);
            return new Decision(Outcome.BLOCKED, seconds);
        } catch (RuntimeException e) {
            alertUnavailable(e);
            return Decision.UNAVAILABLE;
        }
    }

    /** Forget a counter (a successful login clears its failed attempts). Best effort, as hit is. */
    public void clear(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            alertUnavailable(e);
        }
    }

    private static void alertUnavailable(RuntimeException e) {
        logger.error("ALERT rate_limit_unavailable (Redis did not answer, the request goes through unlimited): {}",
                e.toString());
    }
}
