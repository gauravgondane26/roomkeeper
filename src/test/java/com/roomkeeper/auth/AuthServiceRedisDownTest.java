package com.roomkeeper.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.user.Role;
import com.roomkeeper.user.User;
import com.roomkeeper.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What auth does when Redis, which holds refresh tokens, is unreachable. */
class AuthServiceRedisDownTest {

	private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));

	private final AuthProperties properties = new AuthProperties(SECRET, "roomkeeper", Duration.ofMinutes(15),
			Duration.ofDays(7));

	private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	private final UserRepository users = mock(UserRepository.class);

	private final RefreshTokenStore refreshTokens = mock(RefreshTokenStore.class);

	private final AuthService authService = new AuthService(users, passwordEncoder,
			new AccessTokenService(JwtConfig.encoder(JwtConfig.secretKey(SECRET)), properties, clock), refreshTokens,
			properties, clock);

	private final RedisConnectionFailureException redisDown = new RedisConnectionFailureException("Redis is down");

	@Test
	void loginStillWorksButWithoutARefreshToken() {
		User user = new User("guest@example.com", passwordEncoder.encode("correct-horse-battery"), "Guest", Role.GUEST,
				clock.instant());
		ReflectionTestUtils.setField(user, "id", 7L);
		when(users.findByEmail("guest@example.com")).thenReturn(Optional.of(user));
		when(refreshTokens.issue(anyLong())).thenThrow(redisDown);

		TokenResponse tokens = authService.login(new LoginRequest("guest@example.com", "correct-horse-battery"));

		assertThat(tokens.accessToken()).isNotBlank();
		assertThat(tokens.refreshToken()).isNull();
		assertThat(tokens.refreshExpiresIn()).isNull();
	}

	@Test
	void refreshIsTemporarilyUnavailable() {
		when(refreshTokens.consume(anyString())).thenThrow(redisDown);

		assertThatThrownBy(() -> authService.refresh(new RefreshRequest("some-token")))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemType.SERVICE_UNAVAILABLE));
	}

}
