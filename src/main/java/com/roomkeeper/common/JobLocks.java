package com.roomkeeper.common;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Locks in Redis ({@code lock:{job}}) so a scheduled job runs on one instance at a time.
 * Each instance tries with SET NX and a lease; whoever gets it runs the job, the rest skip
 * that run. The lease bounds how long a crashed instance can hold the lock, so it should
 * be shorter than the job's interval. If a run outlives its lease another instance may
 * start the job too, so jobs must stay safe to run twice (their row locks see to that).
 * <p>
 * If Redis is down, the run is skipped and the next one catches up: Postgres, not the
 * lock, is what keeps the data right.
 */
@Component
public class JobLocks {

	private static final Logger log = LoggerFactory.getLogger(JobLocks.class);

	/** Deletes the lock only if it's still ours, not one another instance took after our lease ran out. */
	private static final RedisScript<Long> RELEASE = RedisScript.of("""
			if redis.call('get', KEYS[1]) == ARGV[1] then
			  return redis.call('del', KEYS[1])
			end
			return 0""", Long.class);

	private final StringRedisTemplate redis;

	public JobLocks(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** Runs {@code job} if this instance gets the lock, holding it for at most {@code lease}; empty if skipped. */
	public <T> Optional<T> runLocked(String name, Duration lease, Supplier<T> job) {
		String key = "lock:" + name;
		String token = UUID.randomUUID().toString();
		try {
			if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, lease))) {
				return Optional.empty();
			}
		}
		catch (DataAccessException ex) {
			log.warn("Redis unavailable; skipping this run of {}", name, ex);
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(job.get());
		}
		finally {
			release(key, token);
		}
	}

	private void release(String key, String token) {
		try {
			redis.execute(RELEASE, List.of(key), token);
		}
		catch (DataAccessException ex) {
			// It expires at the end of its lease anyway
			log.warn("Could not release {}", key, ex);
		}
	}

}
