package com.roomkeeper.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prod's Redis holds idempotency records and rate-limit buckets, so prod refuses to start
 * without one that asks for a password: given as REDIS_HOST and REDIS_PASSWORD (Compose), or
 * as one URL carrying the password (SPRING_DATA_REDIS_URL, as Render's Key Value hands it
 * over once internal authentication is on).
 */
class ProductionRedisCheckTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ProductionRedisCheck.class)
		.withPropertyValues("spring.profiles.active=prod");

	@Test
	void startsWithAHostAndAPassword() {
		runner.withPropertyValues("spring.data.redis.host=redis", "spring.data.redis.password=secret")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void refusesAHostWithoutAPassword() {
		runner.withPropertyValues("spring.data.redis.host=redis", "spring.data.redis.password=")
			.run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("password"));
	}

	@Test
	void startsWithAUrlCarryingAPassword() {
		runner.withPropertyValues("spring.data.redis.url=redis://default:secret@red-abc123:6379")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void refusesAUrlWithoutAPassword() {
		runner.withPropertyValues("spring.data.redis.url=redis://red-abc123:6379")
			.run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("password"));
		runner.withPropertyValues("spring.data.redis.url=redis://default:@red-abc123:6379")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void refusesNoRedisAtAll() {
		// Everything that uses Redis carries on without it, so a missing one would go unnoticed
		runner.withPropertyValues("spring.data.redis.host=").run(context -> assertThat(context).hasFailed()
			.getFailure()
			.rootCause()
			.hasMessageContaining("REDIS_HOST"));
	}

	@Test
	void onlyProdIsChecked() {
		new ApplicationContextRunner().withUserConfiguration(ProductionRedisCheck.class)
			.withPropertyValues("spring.data.redis.host=localhost")
			.run(context -> assertThat(context).hasNotFailed());
	}

}
