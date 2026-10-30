package com.roomkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

import javax.crypto.SecretKey;

import com.roomkeeper.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The access token checks, on the decoder the app uses: only an HS256 token signed with
 * the app's key, from its issuer, inside its lifetime (30 s allowed for clock skew), is
 * accepted. Anything else is a JwtException, which the resource server answers with 401.
 */
class JwtDecoderEdgeCasesTest {

	private static final String SECRET = Base64.getEncoder().encodeToString(bytes(64, 1));

	private static final String ISSUER = "roomkeeper";

	private static final Instant NOW = Instant.parse("2026-03-10T09:00:00Z");

	private final SecretKey key = JwtConfig.secretKey(SECRET);

	private final JwtDecoder decoder = JwtConfig.decoder(key, ISSUER, new MutableClock(NOW));

	@Test
	void aTokenTheAppIssuedIsAccepted() {
		var jwt = decoder.decode(token(claims -> claims));

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsStringList(JwtConfig.ROLES_CLAIM)).containsExactly("GUEST");
	}

	@Test
	void anExpiredTokenIsRejected() {
		assertRejected(token(claims -> claims.issuedAt(NOW.minus(Duration.ofMinutes(16)))
			.expiresAt(NOW.minusSeconds(31))));
	}

	@Test
	void aTokenExpiredWithinTheClockSkewIsStillAccepted() {
		var jwt = decoder.decode(token(claims -> claims.issuedAt(NOW.minus(Duration.ofMinutes(15)))
			.expiresAt(NOW.minusSeconds(20))));

		assertThat(jwt.getSubject()).isEqualTo("42");
	}

	@Test
	void aTokenNotValidYetIsRejected() {
		assertRejected(token(claims -> claims.notBefore(NOW.plus(Duration.ofMinutes(5)))));
	}

	@Test
	void aTokenFromAnotherIssuerIsRejected() {
		assertRejected(token(claims -> claims.issuer("someone-else")));
	}

	@Test
	void aTokenWithoutAnIssuerIsRejected() {
		assertRejected(token(claims -> claims.claims(all -> all.remove("iss"))));
	}

	@Test
	void aTokenSignedWithAnotherKeyIsRejected() {
		var otherKey = JwtConfig.secretKey(Base64.getEncoder().encodeToString(bytes(64, 2)));

		assertRejected(token(otherKey, MacAlgorithm.HS256, claims -> claims));
	}

	@Test
	void aTokenSignedWithTheRightKeyButAnotherAlgorithmIsRejected() {
		assertRejected(token(key, MacAlgorithm.HS512, claims -> claims));
	}

	@Test
	void anUnsignedTokenIsRejected() {
		String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
		String payload = base64Url("{\"iss\":\"roomkeeper\",\"sub\":\"42\",\"exp\":" + NOW.plusSeconds(600).getEpochSecond()
				+ ",\"roles\":[\"ADMIN\"]}");

		assertRejected(header + "." + payload + ".");
	}

	@Test
	void aTokenWhoseRoleWasEditedIsRejected() {
		String[] parts = token(claims -> claims).split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		String promoted = base64Url(payload.replace("\"GUEST\"", "\"ADMIN\""));
		assertThat(promoted).isNotEqualTo(parts[1]);

		assertRejected(parts[0] + "." + promoted + "." + parts[2]);
	}

	@Test
	void somethingThatIsntAJwtIsRejected() {
		assertRejected("not-a-jwt");
	}

	private void assertRejected(String token) {
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	private String token(UnaryOperator<JwtClaimsSet.Builder> changes) {
		return token(key, MacAlgorithm.HS256, changes);
	}

	/** A token like the ones the app issues (see AccessTokenService), with changes. */
	private static String token(SecretKey signingKey, MacAlgorithm algorithm, UnaryOperator<JwtClaimsSet.Builder> changes) {
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer(ISSUER)
			.subject("42")
			.issuedAt(NOW)
			.expiresAt(NOW.plus(Duration.ofMinutes(15)))
			.claim(JwtConfig.ROLES_CLAIM, List.of("GUEST"));
		return JwtConfig.encoder(signingKey)
			.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), changes.apply(claims).build()))
			.getTokenValue();
	}

	private static String base64Url(String json) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] bytes(int length, int value) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

}
