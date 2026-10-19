package comp5348.storeservice.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用的"可以手动拨的表"。
 * 生产代码以后从 Spring 拿 Clock 来判断"过了 10 分钟没有"、"超时 15 分钟没有"；
 * 测试里注入这个，想过 10 分钟就 advance(10 分钟)，不用真等。
 */
public class MutableClock extends Clock {

    // volatile：#7、#13 这种多线程测试里，别的线程也能马上看到新的时间
    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** 把表往前拨一段时间 */
    public void advance(Duration d) {
        now = now.plus(d);
    }

    /** 把表设成某个时刻（每个测试开头重置成"现在"） */
    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
