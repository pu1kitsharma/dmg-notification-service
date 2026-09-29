package com.dmg.notify.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** System time plus an adjustable offset, so tests can "wait" for backoff/schedules by moving time, not sleeping. */
public class MutableClock extends Clock {
    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration d) { offset = offset.plus(d); }
    public void reset() { offset = Duration.ZERO; }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.now().plus(offset); }

    @TestConfiguration
    public static class Config {
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(); }
    }
}
