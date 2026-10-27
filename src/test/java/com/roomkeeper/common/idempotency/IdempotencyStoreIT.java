package com.roomkeeper.common.idempotency;

import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Idempotency keys in Redis: {@code idem:{userId}:{key}}, claimed with SET NX. */
class IdempotencyStoreIT extends AbstractIntegrationTest {

	private static final StoredResponse CREATED = new StoredResponse(201,
			Map.of("Location", "/api/v1/bookings/7", "Content-Type", "application/json"), "{\"id\":7}");

	@Autowired
	IdempotencyStore store;

	@Autowired
	StringRedisTemplate redis;

	@Test
	void theFirstRequestClaimsTheKeyForAShortLease() {
		assertThat(store.claim(42, "key-1", "request-a")).isInstanceOf(Claim.Claimed.class);

		// A crashed request can't block its key for a day
		assertThat(redis.getExpire("idem:42:key-1")).isBetween(110L, 120L);
	}

	@Test
	void aRetryWhileTheFirstRequestRunsIsInProgress() {
		store.claim(42, "key-1", "request-a");

		assertThat(store.claim(42, "key-1", "request-a")).isInstanceOf(Claim.InProgress.class);
	}

	@Test
	void aRetryAfterTheFirstRequestFinishedGetsItsResponseForADay() {
		store.claim(42, "key-1", "request-a");
		store.complete(42, "key-1", "request-a", CREATED);

		assertThat(store.claim(42, "key-1", "request-a")).isEqualTo(new Claim.Completed(CREATED));
		assertThat(redis.getExpire("idem:42:key-1")).isBetween(86_390L, 86_400L);
	}

	@Test
	void theSameKeyForADifferentRequestIsRefused() {
		store.claim(42, "key-1", "request-a");
		assertThat(store.claim(42, "key-1", "request-b")).isInstanceOf(Claim.Reused.class);

		store.complete(42, "key-1", "request-a", CREATED);
		assertThat(store.claim(42, "key-1", "request-b")).isInstanceOf(Claim.Reused.class);
	}

	@Test
	void aReleasedKeyCanBeClaimedAgain() {
		store.claim(42, "key-1", "request-a");

		store.release(42, "key-1");

		assertThat(store.claim(42, "key-1", "request-a")).isInstanceOf(Claim.Claimed.class);
	}

	@Test
	void keysBelongToOneUser() {
		store.claim(42, "key-1", "request-a");

		assertThat(store.claim(43, "key-1", "request-a")).isInstanceOf(Claim.Claimed.class);
	}

}
