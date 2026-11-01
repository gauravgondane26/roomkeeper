package com.roomkeeper.common;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How prod reads its Redis settings from the environment. That a password is there at all
 * is checked at startup (ProductionRedisCheck), since it may come in a URL instead. TLS is a
 * switch (REDIS_SSL) for a managed Redis that offers it.
 */
class ProdRedisConfigTest {

	@Test
	void hostAndPasswordAreEmptyUnlessGiven() throws IOException {
		StandardEnvironment prod = prodWith(Map.of());

		assertThat(prod.getProperty("spring.data.redis.host")).isEmpty();
		assertThat(prod.getProperty("spring.data.redis.password")).isEmpty();
		assertThat(prod.getProperty("spring.data.redis.port")).isEqualTo("6379");
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
