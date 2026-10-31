package com.roomkeeper.outbox;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reader blocks on its stream for read-block at a time. Redis commands give up after
 * spring.data.redis.timeout, so a block that long or longer would fail every idle read.
 */
class OutboxReadBlockCheckTest {

	@Test
	void startsWhenTheBlockIsShorterThanTheRedisTimeout() {
		runner(Duration.ofMillis(500), "1s").run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void refusesABlockAsLongAsTheRedisTimeoutOrLonger() {
		for (String timeout : new String[] { "500ms", "250ms" }) {
			runner(Duration.ofMillis(500), timeout).run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("roomkeeper.outbox.read-block")
				.hasMessageContaining("spring.data.redis.timeout"));
		}
	}

	@Test
	void comparesWithRedisDefaultTimeoutWhenNoneIsSet() {
		// Spring's default is 60 s
		runner(Duration.ofSeconds(59), null).run(context -> assertThat(context).hasNotFailed());
		runner(Duration.ofSeconds(60), null).run(context -> assertThat(context).hasFailed());
	}

	private static ApplicationContextRunner runner(Duration readBlock, String redisTimeout) {
		var runner = new ApplicationContextRunner()
			.withBean(OutboxProperties.class, () -> new OutboxProperties(Duration.ofSeconds(30), readBlock,
					Duration.ofSeconds(30), Duration.ofMinutes(2), 10_000, true))
			.withUserConfiguration(OutboxReadBlockCheck.class);
		return redisTimeout == null ? runner : runner.withPropertyValues("spring.data.redis.timeout=" + redisTimeout);
	}

}
