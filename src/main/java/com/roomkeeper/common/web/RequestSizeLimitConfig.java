package com.roomkeeper.common.web;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class RequestSizeLimitConfig {

	/**
	 * First in line (after Boot's character encoding filter), so no other filter, Spring
	 * Security included, reads a body before its size is checked.
	 */
	@Bean
	FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(RequestSizeProperties properties,
			ProblemDetailsWriter problemWriter) {
		FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>(
				new RequestSizeLimitFilter(properties, problemWriter));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

}
