package com.roomkeeper.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When Redis runs out of memory it evicts the keys closest to expiring first (compose.yaml's
 * maxmemory policy, read from that file): cached searches and rate-limit buckets go, while
 * refresh tokens and idempotency keys, which live for days and hours, stay. Without a policy
 * a full Redis refuses writes, and logins and bookings would fail with it.
 */
class RedisEvictionIT {

	private static final Pattern POLICY = Pattern.compile("\"--maxmemory-policy\",\\s*\"([a-z-]+)\"");

	@Test
	void cachedSearchesAreEvictedBeforeRefreshTokensAndIdempotencyKeys() throws IOException {
		try (GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379)
			.withCommand("redis-server", "--maxmemory", "4mb", "--maxmemory-policy", composePolicy())) {
			redis.start();
			RedisClient client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
			try (StatefulRedisConnection<String, String> connection = client.connect()) {
				RedisCommands<String, String> commands = connection.sync();
				commands.set("refresh:token-hash", "42", SetArgs.Builder.ex(7 * 24 * 3600));
				commands.set("idem:42:key", "{\"status\":201}", SetArgs.Builder.ex(24 * 3600));

				// About 8 MB of cached searches into 4 MB
				String page = "x".repeat(4_000);
				for (int i = 0; i < 2_000; i++) {
					commands.set("search:city:v1:" + i, page, SetArgs.Builder.ex(60));
				}

				assertThat(commands.info("stats")).containsPattern("evicted_keys:[1-9]");
				assertThat(commands.exists("refresh:token-hash", "idem:42:key")).isEqualTo(2);
			}
			finally {
				client.shutdown();
			}
		}
	}

	private static String composePolicy() throws IOException {
		Matcher matcher = POLICY.matcher(Files.readString(Path.of("compose.yaml")));
		assertThat(matcher.find()).as("compose.yaml runs Redis with a --maxmemory-policy").isTrue();
		return matcher.group(1);
	}

}
