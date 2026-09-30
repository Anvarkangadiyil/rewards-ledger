package com.anvarkangadiyil.rewards;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock tests can move forward, so expiry can be tested without waiting. */
public class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    public void reset() {
        now.set(START);
    }

    public void advance(Duration d) {
        now.updateAndGet(t -> t.plus(d));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
