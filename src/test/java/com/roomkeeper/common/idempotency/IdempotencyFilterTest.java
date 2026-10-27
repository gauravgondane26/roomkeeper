package com.roomkeeper.common.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdempotencyFilterTest {

	private static final String BODY = "{\"roomId\":5,\"rooms\":1}";

	private final IdempotencyStore store = mock(IdempotencyStore.class);

	private final IdempotencyFilter filter = new IdempotencyFilter(store,
			new ProblemDetailsWriter(JsonMapper.builder().build()), "POST /api/v1/bookings/init",
			"POST /api/v1/bookings/{id}/payments");

	@BeforeEach
	void signIn() {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("42", null, "ROLE_GUEST"));
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void runsTheFirstRequestAndStoresItsResponse() throws Exception {
		when(store.claim(eq(42L), eq("key-1"), anyString())).thenReturn(new Claim.Claimed());
		var response = new MockHttpServletResponse();

		filter.doFilter(request("key-1", BODY), response, respondWith(201, "{\"id\":7}"));

		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getContentAsString()).isEqualTo("{\"id\":7}");
		var stored = ArgumentCaptor.forClass(StoredResponse.class);
		verify(store).complete(eq(42L), eq("key-1"), anyString(), stored.capture());
		assertThat(stored.getValue()).isEqualTo(new StoredResponse(201,
				Map.of("Location", "/api/v1/bookings/7", "Content-Type", "application/json"), "{\"id\":7}"));
	}

	@Test
	void replaysTheStoredResponseWithoutRunningTheRequestAgain() throws Exception {
		when(store.claim(eq(42L), eq("key-1"), anyString())).thenReturn(new Claim.Completed(new StoredResponse(201,
				Map.of("Location", "/api/v1/bookings/7", "Content-Type", "application/json"), "{\"id\":7}")));
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(request("key-1", BODY), response, chain);

		assertThat(chain.getRequest()).as("request not run again").isNull();
		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getHeader("Location")).isEqualTo("/api/v1/bookings/7");
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(response.getHeader("Idempotent-Replayed")).isEqualTo("true");
		assertThat(response.getContentAsString()).isEqualTo("{\"id\":7}");
	}

	@Test
	void releasesTheKeyWhenTheRequestFails() throws Exception {
		// A failed request changed nothing, so the client may retry it with the same key
		when(store.claim(anyLong(), anyString(), anyString())).thenReturn(new Claim.Claimed());
		var response = new MockHttpServletResponse();

		filter.doFilter(request("key-1", BODY), response, respondWith(409, "{\"type\":\"sold-out\"}"));

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getContentAsString()).isEqualTo("{\"type\":\"sold-out\"}");
		verify(store).release(42L, "key-1");
		verify(store, never()).complete(anyLong(), anyString(), anyString(), any());
	}

	@Test
	void releasesTheKeyWhenTheHandlerThrows() {
		when(store.claim(anyLong(), anyString(), anyString())).thenReturn(new Claim.Claimed());
		FilterChain failing = (req, res) -> {
			throw new ServletException("boom");
		};

		assertThatThrownBy(() -> filter.doFilter(request("key-1", BODY), new MockHttpServletResponse(), failing))
			.isInstanceOf(ServletException.class);
		verify(store).release(42L, "key-1");
	}

	@Test
	void aRetryWhileTheFirstRunsIsAConflict() throws Exception {
		when(store.claim(anyLong(), anyString(), anyString())).thenReturn(new Claim.InProgress());
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(request("key-1", BODY), response, chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getHeader("Retry-After")).isEqualTo("1");
		assertThat(response.getContentAsString()).contains("urn:roomkeeper:problem:idempotency-key-in-use");
	}

	@Test
	void theSameKeyForADifferentRequestIsRefused() throws Exception {
		when(store.claim(anyLong(), anyString(), anyString())).thenReturn(new Claim.Reused());
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(request("key-1", BODY), response, chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(response.getContentAsString()).contains("urn:roomkeeper:problem:idempotency-key-reused");
	}

	@Test
	void aMissingKeyIsABadRequest() throws Exception {
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(request(null, BODY), response, chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("urn:roomkeeper:problem:idempotency-key-required");
		verifyNoInteractions(store);
	}

	@Test
	void aKeyMustBeShortVisibleText() throws Exception {
		for (String key : new String[] { "", " ", "x".repeat(256), "line\nbreak", "café" }) {
			var response = new MockHttpServletResponse();

			filter.doFilter(request(key, BODY), response, new MockFilterChain());

			assertThat(response.getStatus()).as(key).isEqualTo(400);
		}
		verifyNoInteractions(store);
	}

	@Test
	void failsClosedWhenRedisIsDown() throws Exception {
		// Better a retry later than a booking or a payment made twice
		when(store.claim(anyLong(), anyString(), anyString())).thenThrow(new RedisConnectionFailureException("down"));
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(request("key-1", BODY), response, chain);

		assertThat(chain.getRequest()).isNull();
		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString()).contains("urn:roomkeeper:problem:service-unavailable");
	}

	@Test
	void theRequestIsIdentifiedByItsPathAndBody() throws Exception {
		when(store.claim(anyLong(), anyString(), anyString())).thenReturn(new Claim.Claimed());

		filter.doFilter(request("key-1", BODY), new MockHttpServletResponse(), new MockFilterChain());
		filter.doFilter(request("key-1", BODY), new MockHttpServletResponse(), new MockFilterChain());
		filter.doFilter(request("key-1", "{\"roomId\":6,\"rooms\":1}"), new MockHttpServletResponse(),
				new MockFilterChain());
		var payments = new MockHttpServletRequest("POST", "/api/v1/bookings/7/payments");
		payments.addHeader("Idempotency-Key", "key-1");
		payments.setContent(BODY.getBytes(StandardCharsets.UTF_8));
		filter.doFilter(payments, new MockHttpServletResponse(), new MockFilterChain());

		var fingerprints = ArgumentCaptor.forClass(String.class);
		verify(store, times(4)).claim(eq(42L), eq("key-1"), fingerprints.capture());
		var seen = fingerprints.getAllValues();
		assertThat(seen.get(1)).isEqualTo(seen.get(0));
		assertThat(seen.get(2)).isNotEqualTo(seen.get(0));
		assertThat(seen.get(3)).isNotEqualTo(seen.get(0));
	}

	@Test
	void theHandlerStillReadsTheBody() throws Exception {
		when(store.claim(anyLong(), anyString(), anyString())).thenReturn(new Claim.Claimed());
		String[] read = new String[1];

		filter.doFilter(request("key-1", BODY), new MockHttpServletResponse(),
				(req, res) -> read[0] = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

		assertThat(read[0]).isEqualTo(BODY);
	}

	@Test
	void otherRoutesPassStraightThrough() throws Exception {
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/bookings/7"), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(store);
	}

	private static MockHttpServletRequest request(String key, String body) {
		var request = new MockHttpServletRequest("POST", "/api/v1/bookings/init");
		if (key != null) {
			request.addHeader("Idempotency-Key", key);
		}
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	/** A handler that answers with the status and JSON body, and a Location for a 201. */
	private static FilterChain respondWith(int status, String body) {
		return (req, res) -> {
			HttpServletResponse response = (HttpServletResponse) res;
			response.setStatus(status);
			if (status == 201) {
				response.setHeader("Location", "/api/v1/bookings/7");
			}
			response.setContentType("application/json");
			write(response, body);
		};
	}

	private static void write(HttpServletResponse response, String body) throws IOException {
		response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
	}

}
