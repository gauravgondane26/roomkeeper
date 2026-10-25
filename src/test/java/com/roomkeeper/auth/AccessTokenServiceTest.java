package com.roomkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.SecretKey;

import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

	private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

	private static final String OTHER_SECRET = Base64.getEncoder()
		.encodeToString("another-32-byte-secret-for-tests".getBytes(StandardCharsets.UTF_8));

	private final MutableClock clock = new MutableClock(NOW);

	private final AuthProperties properties = new AuthProperties(SECRET, "roomkeeper", Duration.ofMinutes(15),
			Duration.ofDays(7));

	private final SecretKey key = JwtConfig.secretKey(SECRET);

	private final AccessTokenService tokens = new AccessTokenService(JwtConfig.encoder(key), properties, clock);

	private final JwtDecoder decoder = JwtConfig.decoder(key, "roomkeeper", clock);

	@Test
	void carriesUserIdRoleIssuerAndFifteenMinuteExpiry() {
		AccessToken token = tokens.issue(42L, Role.HOTEL_MANAGER);

		var jwt = decoder.decode(token.value());
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly("HOTEL_MANAGER");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("roomkeeper");
		assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
		assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
		assertThat(token.expiresIn()).isEqualTo(Duration.ofMinutes(15));
	}

	@Test
	void isAcceptedUntilItExpires() {
		AccessToken token = tokens.issue(42L, Role.GUEST);

		clock.advance(Duration.ofMinutes(15));

		assertThat(decoder.decode(token.value()).getSubject()).isEqualTo("42");
	}

	@Test
	void isRejectedOnceExpiredPastTheAllowedClockSkew() {
		AccessToken token = tokens.issue(42L, Role.GUEST);

		clock.advance(Duration.ofMinutes(15).plusSeconds(31));

		assertThatThrownBy(() -> decoder.decode(token.value())).isInstanceOf(JwtValidationException.class);
	}

	@Test
	void isRejectedWhenSignedWithAnotherKey() {
		var otherKey = JwtConfig.secretKey(OTHER_SECRET);
		var forged = new AccessTokenService(JwtConfig.encoder(otherKey), properties, clock)
			.issue(42L, Role.HOTEL_MANAGER);

		assertThatThrownBy(() -> decoder.decode(forged.value())).isInstanceOf(BadJwtException.class);
	}

	@Test
	void isRejectedFromAnotherIssuer() {
		var otherIssuer = new AuthProperties(SECRET, "someone-else", Duration.ofMinutes(15), Duration.ofDays(7));
		var token = new AccessTokenService(JwtConfig.encoder(key), otherIssuer, clock).issue(42L, Role.GUEST);

		assertThatThrownBy(() -> decoder.decode(token.value())).isInstanceOf(JwtValidationException.class);
	}

	@Test
	void secretMustBeAtLeast256Bits() {
		String shortSecret = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatThrownBy(() -> JwtConfig.secretKey(shortSecret))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("256 bits");
	}

}
