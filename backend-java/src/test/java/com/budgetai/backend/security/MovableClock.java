package com.budgetai.backend.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Un rellotge que el test fa avançar, per provar caducitats sense esperar-les. */
final class MovableClock extends Clock {

    private Instant now;

    MovableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
