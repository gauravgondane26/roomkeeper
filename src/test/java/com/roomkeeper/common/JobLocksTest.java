package com.roomkeeper.common;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobLocksTest {

	@Test
	void skipsTheRunWhenRedisIsDown() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
			.thenThrow(new RedisConnectionFailureException("down"));
		boolean[] ran = { false };

		var result = new JobLocks(redis).runLocked("job", Duration.ofSeconds(30), () -> ran[0] = true);

		assertThat(result).isEmpty();
		assertThat(ran[0]).as("the next run catches up instead").isFalse();
	}

}
