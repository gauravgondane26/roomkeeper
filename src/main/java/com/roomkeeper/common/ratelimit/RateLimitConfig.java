package com.roomkeeper.common.ratelimit;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import com.roomkeeper.common.metrics.BusinessMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

	@Bean
	AuthRateLimitFilter authRateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
			ProblemDetailsWriter problemWriter, BusinessMetrics metrics) {
		return new AuthRateLimitFilter(rateLimiter, properties, problemWriter, metrics);
	}

}
