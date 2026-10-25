package com.roomkeeper.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one source of "now" for the application. Code that needs the current time injects
 * this {@link Clock} instead of calling {@code now()}, so tests can control time.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
