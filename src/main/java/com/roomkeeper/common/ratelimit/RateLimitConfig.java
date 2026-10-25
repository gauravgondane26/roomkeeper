package com.roomkeeper.common.ratelimit;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

	@Bean
	LoginRateLimitFilter loginRateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
			ProblemDetailsWriter problemWriter) {
		return new LoginRateLimitFilter(rateLimiter, properties, problemWriter);
	}

}
