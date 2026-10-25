package com.roomkeeper.common.ratelimit;

import java.io.IOException;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import com.roomkeeper.common.error.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits login attempts per client IP, so passwords can't be guessed quickly. Counts every
 * attempt, successful or not.
 * <p>
 * If Redis is down the request is allowed and a warning logged: availability matters more
 * than strict limits here, and nothing is lost but the protection.
 * <p>
 * The client is identified by the connection's remote address. Behind a reverse proxy,
 * enable forwarded-header handling (server.forward-headers-strategy) for the real client
 * IP; never trust X-Forwarded-For when not behind one, or clients can spoof it.
 * <p>
 * Registered in {@link RateLimitConfig} rather than by component scanning, so web slice
 * tests (which pick up every Filter component) don't need Redis.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

	private static final String LOGIN_PATH = "/api/v1/auth/login";

	private final RateLimiter rateLimiter;

	private final RateLimitProperties properties;

	private final ProblemDetailsWriter problemWriter;

	public LoginRateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
			ProblemDetailsWriter problemWriter) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.problemWriter = problemWriter;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !("POST".equals(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI()));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		RateLimiter.Decision decision;
		try {
			decision = rateLimiter.tryAcquire("rl:login:" + request.getRemoteAddr(), properties.login());
		}
		catch (DataAccessException ex) {
			log.warn("Rate limiter unavailable; allowing login request", ex);
			chain.doFilter(request, response);
			return;
		}
		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}
		long retryAfterSeconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		problemWriter.write(request, response, ProblemType.RATE_LIMITED
			.problem("Too many login attempts; try again in " + retryAfterSeconds + " seconds"));
	}

}
