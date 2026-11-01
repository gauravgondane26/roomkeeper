package com.roomkeeper.common.ratelimit;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Requests allowed per client IP on each open route.
 *
 * @param login POST /api/v1/auth/login
 * @param signup POST /api/v1/auth/signup
 * @param refresh POST /api/v1/auth/refresh
 * @param search GET /api/v1/hotels/search
 * @param failedLogins wrong passwords per account (email), whatever the IP: only failures
 * take from it, and once it's empty every login to the account waits
 * @param holds POST /api/v1/bookings/init, per signed-in guest rather than per IP
 */
@Validated
@ConfigurationProperties("roomkeeper.rate-limits")
public record RateLimitProperties(@NotNull @Valid Rule login, @NotNull @Valid Rule signup,
		@NotNull @Valid Rule refresh, @NotNull @Valid Rule search, @NotNull @Valid Rule failedLogins,
		@NotNull @Valid Rule holds) {

	/** Up to {@code capacity} requests at once, refilling to full over {@code period}. */
	public record Rule(@Min(1) int capacity, @NotNull Duration period) {
	}

}
