package com.roomkeeper.outbox;

import java.time.Duration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with a stream read that blocks as long as Redis commands are allowed to
 * take or longer: every read that found nothing new would time out instead of returning.
 */
@Component
class OutboxReadBlockCheck implements InitializingBean {

	private final OutboxProperties properties;

	private final Duration redisTimeout;

	OutboxReadBlockCheck(OutboxProperties properties, Environment environment) {
		this.properties = properties;
		// Bound as Boot binds properties, so "1s" or "500ms" reads as it does for Redis; Spring's default is 60 s
		this.redisTimeout = Binder.get(environment)
			.bind("spring.data.redis.timeout", Duration.class)
			.orElse(Duration.ofSeconds(60));
	}

	@Override
	public void afterPropertiesSet() {
		if (properties.readBlock().compareTo(redisTimeout) >= 0) {
			throw new IllegalStateException("roomkeeper.outbox.read-block (" + properties.readBlock()
					+ ") must be shorter than spring.data.redis.timeout (" + redisTimeout + ")");
		}
	}

}
