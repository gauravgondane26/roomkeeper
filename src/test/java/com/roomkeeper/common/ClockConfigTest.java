package com.roomkeeper.common;

import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

	@Test
	void providesSystemClockInUtc() {
		var clock = new ClockConfig().clock();

		assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
	}

}
