package com.roomkeeper.common.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency keys in Redis, one per user and key ({@code idem:{userId}:{key}}). Each holds
 * the request's fingerprint and, once it has finished, its response. A request claims its
 * key with SET NX, so of two requests racing with one key only one runs.
 * <p>
 * Redis errors are thrown to the caller, which fails closed.
 */
@Component
public class IdempotencyStore {

	private final StringRedisTemplate redis;

	private final JsonMapper json;

	private final IdempotencyProperties properties;

	IdempotencyStore(StringRedisTemplate redis, JsonMapper json, IdempotencyProperties properties) {
		this.redis = redis;
		this.json = json;
		this.properties = properties;
	}

	/** Claims the key for the request with this fingerprint, or says why it can't run. */
	public Claim claim(long userId, String key, String fingerprint) {
		String redisKey = redisKey(userId, key);
		String running = json.writeValueAsString(new Entry(fingerprint, null));
		// A second attempt covers a key that lapsed or was released between the two calls
		for (int attempt = 0; attempt < 2; attempt++) {
			if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, running, properties.lease()))) {
				return new Claim.Claimed();
			}
			String stored = redis.opsForValue().get(redisKey);
			if (stored != null) {
				Entry entry = json.readValue(stored, Entry.class);
				if (!entry.fingerprint().equals(fingerprint)) {
					return new Claim.Reused();
				}
				return entry.response() == null ? new Claim.InProgress() : new Claim.Completed(entry.response());
			}
		}
		return new Claim.InProgress();
	}

	/** Keeps the finished request's response, for retries over the next {@code ttl}. */
	public void complete(long userId, String key, String fingerprint, StoredResponse response) {
		redis.opsForValue().set(redisKey(userId, key), json.writeValueAsString(new Entry(fingerprint, response)),
				properties.ttl());
	}

	/** Frees the key, so a retry runs the request again. */
	public void release(long userId, String key) {
		redis.delete(redisKey(userId, key));
	}

	private static String redisKey(long userId, String key) {
		return "idem:" + userId + ":" + key;
	}

	/** The response is null while the request is running. */
	private record Entry(String fingerprint, StoredResponse response) {
	}

}
