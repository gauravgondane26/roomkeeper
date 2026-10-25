package com.roomkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh tokens live in Redis as {@code refresh:{sha256(token)} -> userId} with a TTL.
 * <p>
 * Tokens are opaque random values, not JWTs: the server must look them up anyway to
 * support revocation. Only a hash is stored, so someone who can read Redis can't use the
 * tokens they see. If Redis loses this data, users simply log in again.
 */
@Component
public class RefreshTokenStore {

	private static final String KEY_PREFIX = "refresh:";

	private static final int TOKEN_BYTES = 32;

	private final StringRedisTemplate redis;

	private final AuthProperties properties;

	private final SecureRandom random = new SecureRandom();

	public RefreshTokenStore(StringRedisTemplate redis, AuthProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/** Creates and stores a new refresh token for the user and returns it. */
	public String issue(long userId) {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		redis.opsForValue().set(key(token), String.valueOf(userId), properties.refreshTokenTtl());
		return token;
	}

	/**
	 * Removes the token and returns its user, in one atomic step (GETDEL), so a token can
	 * be used at most once even when requests race.
	 */
	public Optional<Long> consume(String token) {
		return Optional.ofNullable(redis.opsForValue().getAndDelete(key(token))).map(Long::valueOf);
	}

	public void revoke(String token) {
		redis.delete(key(token));
	}

	private static String key(String token) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
			return KEY_PREFIX + HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
