package com.roomkeeper.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.roomkeeper.common.metrics.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A reader whose first connection to Redis fails (Redis still starting, or a slow first
 * connection on a starved CPU) keeps trying, rather than its thread dying and its stream
 * never being read again.
 */
class OutboxStreamReadersTest {

	@Test
	void aReaderThatCantConnectAtFirstKeepsTrying() {
		RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
		when(connections.getConnection()).thenThrow(new RedisConnectionFailureException("Redis isn't up yet"))
			.thenReturn(mock(RedisConnection.class));
		OutboxProperties properties = new OutboxProperties(Duration.ofSeconds(30), Duration.ofMillis(100),
				Duration.ofSeconds(30), Duration.ofMinutes(2), 10_000, true);
		OutboxStreamReaders readers = new OutboxStreamReaders(new OutboxRoutes(List.of(new EveryEvent())),
				mock(StringRedisTemplate.class), mock(OutboxDelivery.class), connections, properties,
				new BusinessMetrics(new SimpleMeterRegistry()), Clock.systemUTC());

		readers.start();
		try {
			// One failed attempt, then it connects again after its back-off
			await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> verify(connections, atLeast(2)).getConnection());
		}
		finally {
			readers.stop();
		}
	}

	/** One consumer taking every event type, as OutboxRoutes requires. */
	private static final class EveryEvent implements OutboxConsumer {

		@Override
		public String name() {
			return "every-event";
		}

		@Override
		public Set<OutboxEventType> types() {
			return EnumSet.allOf(OutboxEventType.class);
		}

		@Override
		public void consume(OutboxEvent event) {
		}

	}

}
