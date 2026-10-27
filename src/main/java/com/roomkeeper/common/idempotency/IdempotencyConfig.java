package com.roomkeeper.common.idempotency;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IdempotencyConfig {

	/** The routes that would make something twice if a client retried them. */
	@Bean
	IdempotencyFilter idempotencyFilter(IdempotencyStore store, ProblemDetailsWriter problemWriter) {
		return new IdempotencyFilter(store, problemWriter, "POST /api/v1/bookings/init",
				"POST /api/v1/bookings/{bookingId}/payments");
	}

}
