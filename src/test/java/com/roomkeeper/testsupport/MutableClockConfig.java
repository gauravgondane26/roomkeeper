package com.roomkeeper.testsupport;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application clock with a {@link MutableClock}, for tests of time-based
 * rules: {@code @Import(MutableClockConfig.class)}, then autowire the MutableClock.
 * <p>
 * Every test class importing this shares one Spring context, and so one clock. It's put
 * back to {@link #START} before every test ({@link MutableClockReset}); set it in
 * {@code @BeforeEach} if the test needs another time. Access tokens are checked against
 * this clock too, so log in again after moving it more than 15 minutes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfig {

	public static final Instant START = Instant.parse("2026-01-15T10:00:00Z");

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock(START);
	}

}
