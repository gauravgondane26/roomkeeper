package com.roomkeeper.common.ratelimit;

import java.io.IOException;
import java.util.Map;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.common.metrics.BusinessMetrics;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits the open routes per client IP, each with its own bucket ({@code rl:{route}:{ip}}):
 * login so passwords can't be guessed quickly, signup so it can't be used to create accounts
 * or send emails in bulk, refresh so it can't be hammered, and public search so nobody can
 * sweep it, or dodge the cache with a new query each time and load Postgres. Holds are
 * limited per signed-in guest instead ({@code rl:holds:user:{id}}), so one account can't
 * keep asking for rooms. Every attempt counts, successful or not, and every rejection is
 * counted in the metrics by route.
 * <p>
 * If Redis is down the request is allowed and a warning logged: availability matters more
 * than strict limits here, and nothing is lost but the protection.
 * <p>
 * The client is identified by the request's remote address: the connection's, or, behind a
 * proxy listed in TRUSTED_PROXIES, the address that proxy put in X-Forwarded-For (see
 * application.yaml). X-Forwarded-For from anyone else is ignored, so clients can't spoof it.
 * <p>
 * Registered in {@link RateLimitConfig} rather than by component scanning, so web slice
 * tests (which pick up every Filter component) don't need Redis.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	/** A route's limit, per client IP, or per signed-in user when {@code perUser}. */
	private record LimitedRoute(String name, RateLimitProperties.Rule rule, String tooMany, Counter rejections,
			boolean perUser) {

		LimitedRoute(String name, RateLimitProperties.Rule rule, String tooMany, Counter rejections) {
			this(name, rule, tooMany, rejections, false);
		}

	}

	private final RateLimiter rateLimiter;

	private final ProblemDetailsWriter problemWriter;

	/** By method and path, such as {@code POST /api/v1/auth/login}. */
	private final Map<String, LimitedRoute> routes;

	public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
			ProblemDetailsWriter problemWriter, BusinessMetrics metrics) {
		this.rateLimiter = rateLimiter;
		this.problemWriter = problemWriter;
		this.routes = Map.of(
				"POST /api/v1/auth/login", new LimitedRoute("login", properties.login(), "Too many login attempts",
						metrics.rateLimitRejections("login")),
				"POST /api/v1/auth/signup", new LimitedRoute("signup", properties.signup(), "Too many signups",
						metrics.rateLimitRejections("signup")),
				"POST /api/v1/auth/refresh", new LimitedRoute("refresh", properties.refresh(),
						"Too many token refreshes", metrics.rateLimitRejections("refresh")),
				"GET /api/v1/hotels/search", new LimitedRoute("search", properties.search(), "Too many searches",
						metrics.rateLimitRejections("search")),
				"POST /api/v1/bookings/init", new LimitedRoute("holds", properties.holds(), "Too many holds asked for",
						metrics.rateLimitRejections("holds"), true));
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !routes.containsKey(key(request));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		LimitedRoute route = routes.get(key(request));
		RateLimiter.Decision decision;
		try {
			decision = rateLimiter.tryAcquire("rl:" + route.name() + ":" + client(route, request), route.rule());
		}
		catch (DataAccessException ex) {
			log.warn("Rate limiter unavailable; allowing {} request", route.name(), ex);
			chain.doFilter(request, response);
			return;
		}
		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}
		route.rejections().increment();
		long retryAfterSeconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
		problemWriter.write(request, response, ProblemType.RATE_LIMITED
			.problem(route.tooMany() + "; try again in " + retryAfterSeconds + " seconds"));
	}

	/**
	 * Who the bucket belongs to: the signed-in user for per-user routes (Spring Security has
	 * run by now, and turned away anyone not signed in), otherwise the client's IP.
	 */
	private static String client(LimitedRoute route, HttpServletRequest request) {
		Authentication caller = SecurityContextHolder.getContext().getAuthentication();
		if (route.perUser() && caller != null && caller.isAuthenticated()
				&& !(caller instanceof AnonymousAuthenticationToken)) {
			return "user:" + caller.getName();
		}
		return request.getRemoteAddr();
	}

	private static String key(HttpServletRequest request) {
		return request.getMethod() + " " + request.getRequestURI();
	}

}
