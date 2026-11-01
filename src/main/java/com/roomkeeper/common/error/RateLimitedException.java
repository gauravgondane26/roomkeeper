package com.roomkeeper.common.error;

import java.time.Duration;

/** 429: a limit was reached; the response says when to try again (Retry-After). */
public class RateLimitedException extends ApiException {

	private final long retryAfterSeconds;

	public RateLimitedException(String what, Duration retryAfter) {
		this(what, Math.max(1, (retryAfter.toMillis() + 999) / 1000));
	}

	private RateLimitedException(String what, long retryAfterSeconds) {
		super(ProblemType.RATE_LIMITED, what + "; try again in " + retryAfterSeconds + " seconds");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
