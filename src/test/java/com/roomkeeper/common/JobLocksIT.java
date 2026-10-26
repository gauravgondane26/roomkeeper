package com.roomkeeper.common;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job locks in Redis ({@code lock:{job}}): of several instances running the same job at
 * once, one gets the lock and runs it; the others skip that run.
 */
class JobLocksIT extends AbstractIntegrationTest {

	private static final Duration LEASE = Duration.ofSeconds(30);

	@Autowired
	JobLocks locks;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void onlyOneOfTwoInstancesRunsTheJobAtATime() throws Exception {
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		CompletableFuture<Optional<String>> first = CompletableFuture.supplyAsync(() -> locks.runLocked("test-job",
				LEASE, () -> {
					running.countDown();
					await(finish);
					return "first";
				}));
		await(running);

		Optional<String> second = locks.runLocked("test-job", LEASE, () -> "second");
		finish.countDown();

		assertThat(second).as("skipped while the first holds the lock").isEmpty();
		assertThat(first.get(5, TimeUnit.SECONDS)).contains("first");
		assertThat(locks.runLocked("test-job", LEASE, () -> "next")).as("the lock is released after").contains("next");
	}

	@Test
	void theLockExpiresAfterItsLease() {
		locks.runLocked("leased-job", LEASE, () -> {
			assertThat(redis.getExpire("lock:leased-job", TimeUnit.SECONDS)).isBetween(1L, 30L);
			return null;
		});
	}

	@Test
	void anInstanceWhoseLeaseRanOutDoesntReleaseTheNextInstancesLock() {
		locks.runLocked("slow-job", LEASE, () -> {
			// This run outlived its lease, and another instance took the lock meanwhile
			redis.opsForValue().set("lock:slow-job", "another-instance");
			return null;
		});

		assertThat(redis.opsForValue().get("lock:slow-job")).isEqualTo("another-instance");
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
