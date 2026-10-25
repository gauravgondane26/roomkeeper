package com.roomkeeper.common.ratelimit;

import java.time.Duration;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LoginRateLimitFilterTest {

	private final RateLimiter rateLimiter = mock(RateLimiter.class);

	private final LoginRateLimitFilter filter = new LoginRateLimitFilter(rateLimiter,
			new RateLimitProperties(new RateLimitProperties.Rule(10, Duration.ofMinutes(1))),
			new ProblemDetailsWriter(JsonMapper.builder().build()));

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
	void ignoresOtherRoutes() throws Exception {
		var chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/signup"), new MockHttpServletResponse(),
				chain);

		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(rateLimiter);
	}

}
