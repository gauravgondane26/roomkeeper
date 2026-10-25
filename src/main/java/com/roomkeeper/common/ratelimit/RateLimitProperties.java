package com.roomkeeper.common.ratelimit;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param login attempts allowed on POST /api/v1/auth/login, per client IP */
@Validated
@ConfigurationProperties("roomkeeper.rate-limits")
public record RateLimitProperties(@NotNull @Valid Rule login) {

	/** Up to {@code capacity} requests at once, refilling to full over {@code period}. */
	public record Rule(@Min(1) int capacity, @NotNull Duration period) {
	}

}
