package com.roomkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.roomkeeper.common.error.RateLimitedException;
import com.roomkeeper.common.metrics.BusinessMetrics;
import com.roomkeeper.common.ratelimit.RateLimitProperties;
import com.roomkeeper.common.ratelimit.RateLimiter;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Wrong passwords per account ({@code rl:failed-login:{sha256(email)}}), on top of the
 * per-IP login limit: from many addresses at once, each under its own limit, a password
 * could otherwise be guessed without end. Only failures take from the bucket; once it's
 * empty every login to the account waits, the right password included, until it refills.
 * <p>
 * Keyed by the email whether or not an account has it, so the limit doesn't tell anyone
 * which emails are registered. The key holds a hash, not the address. If Redis is down,
 * logins go ahead, as with the other limits.
 */
@Component
class FailedLogins {

	private static final Logger log = LoggerFactory.getLogger(FailedLogins.class);

	private final RateLimiter rateLimiter;

	private final RateLimitProperties.Rule rule;

	private final Counter rejections;

	FailedLogins(RateLimiter rateLimiter, RateLimitProperties properties, BusinessMetrics metrics) {
		this.rateLimiter = rateLimiter;
		this.rule = properties.failedLogins();
		this.rejections = metrics.rateLimitRejections("failed-logins");
	}

	/** Refuses the login (429) while the account has used up its wrong passwords. */
	void checkAllowed(String normalizedEmail) {
		RateLimiter.Decision decision;
		try {
			decision = rateLimiter.check(key(normalizedEmail), rule);
		}
		catch (DataAccessException ex) {
			log.warn("Failed-login limit unavailable; allowing the login", ex);
			return;
		}
		if (!decision.allowed()) {
			rejections.increment();
			throw new RateLimitedException("Too many failed logins for this account", decision.retryAfter());
		}
	}

	/** Counts a wrong password against the account. */
	void recordFailure(String normalizedEmail) {
		try {
			rateLimiter.tryAcquire(key(normalizedEmail), rule);
		}
		catch (DataAccessException ex) {
			log.warn("Failed-login limit unavailable; not counting the failure", ex);
		}
	}

	private static String key(String normalizedEmail) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalizedEmail.getBytes(StandardCharsets.UTF_8));
			return "rl:failed-login:" + HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
