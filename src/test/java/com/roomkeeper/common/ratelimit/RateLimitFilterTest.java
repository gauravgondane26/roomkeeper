package com.roomkeeper.common.ratelimit;

import java.time.Duration;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import com.roomkeeper.common.metrics.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

	private static final RateLimitProperties.Rule LOGIN = new RateLimitProperties.Rule(10, Duration.ofMinutes(1));

	private static final RateLimitProperties.Rule SIGNUP = new RateLimitProperties.Rule(20, Duration.ofHours(1));

	private static final RateLimitProperties.Rule REFRESH = new RateLimitProperties.Rule(30, Duration.ofMinutes(1));

	private static final RateLimitProperties.Rule SEARCH = new RateLimitProperties.Rule(300, Duration.ofMinutes(1));

	private final RateLimiter rateLimiter = mock(RateLimiter.class);

	private final RateLimitFilter filter = new RateLimitFilter(rateLimiter,
			new RateLimitProperties(LOGIN, SIGNUP, REFRESH, SEARCH), new ProblemDetailsWriter(JsonMapper.builder().build()),
			new BusinessMetrics(new SimpleMeterRegistry()));

	@Test
	void failsOpenWhenRedisIsDown() throws Exception {
		when(rateLimiter.tryAcquire(anyString(), any())).thenThrow(new RedisConnectionFailureException("down"));
		var chain = new MockFilterChain();
		var response = new MockHttpServletResponse();

		filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/login"), response, chain);

		assertThat(chain.getRequest()).as("request passed on").isNotNull();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void eachRouteUsesItsOwnBucketAndRule() throws Exception {
		when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(new RateLimiter.Decision(true, Duration.ZERO));

		for (String route : new String[] { "login", "signup", "refresh" }) {
			var request = new MockHttpServletRequest("POST", "/api/v1/auth/" + route);
			request.setRemoteAddr("203.0.113.7");
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
		}

		verify(rateLimiter).tryAcquire(eq("rl:login:203.0.113.7"), eq(LOGIN));
		verify(rateLimiter).tryAcquire(eq("rl:signup:203.0.113.7"), eq(SIGNUP));
		verify(rateLimiter).tryAcquire(eq("rl:refresh:203.0.113.7"), eq(REFRESH));
	}

	@Test
	void publicSearchIsLimitedWithItsOwnBucket() throws Exception {
		when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(new RateLimiter.Decision(true, Duration.ZERO));
		var request = new MockHttpServletRequest("GET", "/api/v1/hotels/search");
		request.setRemoteAddr("203.0.113.7");

		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		verify(rateLimiter).tryAcquire(eq("rl:search:203.0.113.7"), eq(SEARCH));
	}

	@Test
	void ignoresOtherRoutes() throws Exception {
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/logout"), new MockHttpServletResponse(),
				chain);
		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/login"), new MockHttpServletResponse(),
				new MockFilterChain());
		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/hotels/7/info"), new MockHttpServletResponse(),
				new MockFilterChain());
		filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/hotels/search"), new MockHttpServletResponse(),
				new MockFilterChain());

		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(rateLimiter);
	}

}
