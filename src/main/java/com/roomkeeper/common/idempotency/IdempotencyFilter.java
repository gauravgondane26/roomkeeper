package com.roomkeeper.common.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Makes retries of the routes it covers safe. The client sends an {@code Idempotency-Key}
 * header, and a retry with the same key gets the first response back instead of running
 * again: no second booking, no second checkout session. Keys belong to the caller and are
 * kept for a day.
 * <p>
 * Only a successful (2xx) response is kept. A failed request changed nothing, so its key is
 * released and the client may retry with it. The same key on a different request (another
 * path or body) is refused with 422, and a retry while the first request still runs gets 409.
 * <p>
 * If Redis is down the request is refused with 503: better a retry later than a booking or
 * a payment made twice.
 * <p>
 * Runs after Spring Security, so the caller is known. Registered in {@link IdempotencyConfig}
 * rather than by component scanning, so web slice tests (which pick up every Filter
 * component) don't need Redis.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

	static final String HEADER = "Idempotency-Key";

	static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

	private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

	private record Route(String method, PathPattern path) {

		boolean matches(HttpServletRequest request) {
			return method.equals(request.getMethod()) && path.matches(PathContainer.parsePath(request.getRequestURI()));
		}

	}

	private final IdempotencyStore store;

	private final ProblemDetailsWriter problemWriter;

	private final List<Route> routes;

	/** Covers each route, given as {@code "POST /api/v1/bookings/{id}/payments"}. */
	public IdempotencyFilter(IdempotencyStore store, ProblemDetailsWriter problemWriter, String... routes) {
		this.store = store;
		this.problemWriter = problemWriter;
		this.routes = Arrays.stream(routes).map(route -> {
			String[] methodAndPath = route.split(" ", 2);
			return new Route(methodAndPath[0], PathPatternParser.defaultInstance.parse(methodAndPath[1]));
		}).toList();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return routes.stream().noneMatch(route -> route.matches(request));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication caller = SecurityContextHolder.getContext().getAuthentication();
		if (caller == null || caller instanceof AnonymousAuthenticationToken) {
			// Not signed in: Spring Security answers that, after this filter
			chain.doFilter(request, response);
			return;
		}
		String key = request.getHeader(HEADER);
		if (key == null || !VALID_KEY.matcher(key).matches()) {
			problemWriter.write(request, response, ProblemType.IDEMPOTENCY_KEY_REQUIRED.problem("Send an " + HEADER
					+ " header of 1 to 255 visible ASCII characters, new for each request and the same when retrying it"));
			return;
		}
		long userId = Long.parseLong(caller.getName());
		BufferedRequest buffered = new BufferedRequest(request);
		String fingerprint = buffered.fingerprint();
		Claim claim;
		try {
			claim = store.claim(userId, key, fingerprint);
		}
		catch (DataAccessException ex) {
			log.warn("Idempotency keys unavailable; refusing {} {}", request.getMethod(), request.getRequestURI(), ex);
			problemWriter.write(request, response, ProblemType.SERVICE_UNAVAILABLE
				.problem("Can't check the " + HEADER + " right now; try again shortly"));
			return;
		}
		switch (claim) {
			case Claim.Claimed() -> run(buffered, response, chain, userId, key, fingerprint);
			case Claim.Completed(StoredResponse stored) -> replay(stored, response);
			case Claim.InProgress() -> {
				response.setHeader(HttpHeaders.RETRY_AFTER, "1");
				problemWriter.write(request, response, ProblemType.IDEMPOTENCY_KEY_IN_USE
					.problem("A request with this " + HEADER + " is still running; retry it shortly"));
			}
			case Claim.Reused() -> problemWriter.write(request, response, ProblemType.IDEMPOTENCY_KEY_REUSED
				.problem("This " + HEADER + " was used for a different request; send a new key for a new request"));
		}
	}

	private void run(BufferedRequest request, HttpServletResponse response, FilterChain chain, long userId, String key,
			String fingerprint) throws ServletException, IOException {
		ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
		StoredResponse succeeded = null;
		try {
			chain.doFilter(request, captured);
			if (captured.getStatus() / 100 == 2) {
				succeeded = toStored(captured);
			}
		}
		finally {
			record(userId, key, fingerprint, succeeded);
			captured.copyBodyToResponse();
		}
	}

	/** Keeps a success for retries, or frees the key after a failure. */
	private void record(long userId, String key, String fingerprint, StoredResponse succeeded) {
		try {
			if (succeeded != null) {
				store.complete(userId, key, fingerprint, succeeded);
			}
			else {
				store.release(userId, key);
			}
		}
		catch (DataAccessException ex) {
			// The response stands; the key's lease lapses, and a retry after that runs again
			log.warn("Couldn't record the outcome of a request with {} {}", HEADER, key, ex);
		}
	}

	private static StoredResponse toStored(ContentCachingResponseWrapper response) {
		Map<String, String> headers = new LinkedHashMap<>();
		if (response.getHeader(HttpHeaders.LOCATION) != null) {
			headers.put(HttpHeaders.LOCATION, response.getHeader(HttpHeaders.LOCATION));
		}
		if (response.getContentType() != null) {
			headers.put(HttpHeaders.CONTENT_TYPE, response.getContentType());
		}
		return new StoredResponse(response.getStatus(), headers,
				new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
	}

	private static void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
		response.setStatus(stored.status());
		stored.headers().forEach(response::setHeader);
		response.setHeader(REPLAYED_HEADER, "true");
		response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
	}

}
