package com.roomkeeper.common;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Prod's Redis holds idempotency records and rate-limit buckets, so it must ask for a
 * password: prod won't start without REDIS_PASSWORD rather than connect without one. TLS is
 * a switch (REDIS_SSL) for a managed Redis that offers it.
 */
class ProdRedisConfigTest {

	@Test
	void prodNeedsARedisPassword() throws IOException {
		StandardEnvironment prod = prodWith(Map.of());

		assertThatIllegalArgumentException().isThrownBy(() -> prod.getProperty("spring.data.redis.password"))
			.withMessageContaining("REDIS_PASSWORD");
	}

	@Test
	void prodUsesTheRedisPasswordGiven() throws IOException {
		assertThat(prodWith(Map.of("REDIS_PASSWORD", "from-the-environment")).getProperty("spring.data.redis.password"))
			.isEqualTo("from-the-environment");
	}

	@Test
	void tlsToRedisIsASwitchThatStartsOff() throws IOException {
		assertThat(prodWith(Map.of()).getProperty("spring.data.redis.ssl.enabled")).isEqualTo("false");
		assertThat(prodWith(Map.of("REDIS_SSL", "true")).getProperty("spring.data.redis.ssl.enabled")).isEqualTo("true");
	}

	/** application-prod.yaml with only {@code variables} in the environment. */
	private static StandardEnvironment prodWith(Map<String, Object> variables) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		var sources = environment.getPropertySources();
		sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		sources.addFirst(new MapPropertySource("variables", variables));
		new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yaml"))
			.forEach(sources::addLast);
		return environment;
	}

}
