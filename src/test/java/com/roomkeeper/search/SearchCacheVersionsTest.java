package com.roomkeeper.search;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** With Redis down, search goes without the cache, and eviction doesn't fail the change that triggered it. */
class SearchCacheVersionsTest {

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);

	private final SearchCacheVersions versions = versionsBackedBy(values);

	@Test
	void aCityWithNoChangesYetIsVersionZero() {
		when(values.get(anyString())).thenReturn(null);

		assertThat(versions.current("Goa")).isZero();
	}

	@Test
	void withRedisDownThereIsNoVersionSoSearchSkipsTheCache() {
		when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("Redis is down"));

		assertThat(versions.current("Goa")).isNull();
	}

	@Test
	void withRedisDownBumpingTheVersionDoesNotThrow() {
		when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("Redis is down"));

		assertThatNoException().isThrownBy(() -> versions.bump("Goa"));
	}

	private static SearchCacheVersions versionsBackedBy(ValueOperations<String, String> values) {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenReturn(values);
		return new SearchCacheVersions(redis);
	}

}
