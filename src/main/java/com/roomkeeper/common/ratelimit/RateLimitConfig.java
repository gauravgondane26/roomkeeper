package com.roomkeeper.common.ratelimit;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

	@Bean
	AuthRateLimitFilter authRateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
			ProblemDetailsWriter problemWriter) {
		return new AuthRateLimitFilter(rateLimiter, properties, problemWriter);
	}

}
