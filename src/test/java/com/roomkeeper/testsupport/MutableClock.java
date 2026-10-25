package com.roomkeeper.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A UTC clock that only moves when a test moves it. Use it in place of the application
 * clock to test time-based rules (hold expiry, cancellation windows) instantly.
 */
public final class MutableClock extends Clock {

	private volatile Instant instant;

	public MutableClock(Instant start) {
		this.instant = start;
	}

	public void advance(Duration duration) {
		instant = instant.plus(duration);
	}

	public void setInstant(Instant instant) {
		this.instant = instant;
	}

	@Override
	public Instant instant() {
		return instant;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("MutableClock is always UTC");
	}

}
