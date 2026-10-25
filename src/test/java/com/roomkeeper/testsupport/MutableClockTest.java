package com.roomkeeper.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MutableClockTest {

	private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");

	@Test
	void staysAtTheStartInstantUntilMoved() {
		var clock = new MutableClock(START);

		assertThat(clock.instant()).isEqualTo(START);
		assertThat(clock.instant()).isEqualTo(START);
		assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	void advancesByADuration() {
		var clock = new MutableClock(START);

		clock.advance(Duration.ofMinutes(10));

		assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofMinutes(10)));
	}

	@Test
	void canBeSetToAnInstant() {
		var clock = new MutableClock(START);
		var later = Instant.parse("2026-03-01T00:00:00Z");

		clock.setInstant(later);

		assertThat(clock.instant()).isEqualTo(later);
	}

}
