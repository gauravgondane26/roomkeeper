package com.roomkeeper.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.roomkeeper.common.LuaScripts;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiting in Redis. The check and the decrement run as one Lua script,
 * so concurrent requests can't both take the last token. Time comes from the injected
 * {@link Clock}, which keeps the limiter testable.
 */
@Component
public class RateLimiter {

	public record Decision(boolean allowed, Duration retryAfter) {
	}

	@SuppressWarnings("rawtypes")
	private static final RedisScript<List> TOKEN_BUCKET = LuaScripts.load("ratelimit/token_bucket.lua", List.class);

	private final StringRedisTemplate redis;

	private final Clock clock;

	public RateLimiter(StringRedisTemplate redis, Clock clock) {
		this.redis = redis;
		this.clock = clock;
	}

	/** Takes one token from the bucket at {@code key}. Throws if Redis is unreachable. */
	public Decision tryAcquire(String key, RateLimitProperties.Rule rule) {
		List<?> result = redis.execute(TOKEN_BUCKET, List.of(key), String.valueOf(rule.capacity()),
				String.valueOf(rule.period().toMillis()), String.valueOf(clock.millis()));
		boolean allowed = ((Number) result.get(0)).longValue() == 1;
		Duration retryAfter = Duration.ofMillis(((Number) result.get(1)).longValue());
		return new Decision(allowed, retryAfter);
	}

}
