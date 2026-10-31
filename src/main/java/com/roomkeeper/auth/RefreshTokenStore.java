package com.roomkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.roomkeeper.common.LuaScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Refresh tokens live in Redis as {@code refresh:{sha256(token)} -> userId:familyId} with
 * a TTL. A family is one login's chain of rotated tokens: {@code refresh-family:{familyId}}
 * holds the hash of its current token, and only that token can refresh.
 * <p>
 * An older token of the family coming back means two parties hold the chain, the user and
 * whoever copied a token, and the server can't tell which is which. So the family is
 * deleted and every token in it stops working, the newest included; the user logs in
 * again. Rotated tokens stay stored until their own TTL so reuse is recognised that long.
 * <p>
 * Tokens are opaque random values, not JWTs: the server must look them up anyway to
 * support revocation. Only a hash is stored, so someone who can read Redis can't use the
 * tokens they see. If Redis loses this data, users simply log in again.
 */
@Component
public class RefreshTokenStore {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

	private static final String KEY_PREFIX = "refresh:";

	private static final String FAMILY_PREFIX = "refresh-family:";

	private static final int TOKEN_BYTES = 32;

	private static final int FAMILY_BYTES = 16;

	private static final RedisScript<Long> ROTATE = LuaScripts.load("auth/refresh_rotate.lua", Long.class);

	/** A refresh that worked: whose session it is, and the token that now continues it. */
	public record Rotation(long userId, String refreshToken) {
	}

	private record Owner(long userId, String familyId) {

		static Optional<Owner> parse(String value) {
			String[] parts = value.split(":", 2);
			// Tokens stored before families had no family id; they are treated as unknown
			return parts.length == 2 ? Optional.of(new Owner(Long.parseLong(parts[0]), parts[1])) : Optional.empty();
		}

		String value() {
			return userId + ":" + familyId;
		}

	}

	private final StringRedisTemplate redis;

	private final AuthProperties properties;

	private final SecureRandom random = new SecureRandom();

	public RefreshTokenStore(StringRedisTemplate redis, AuthProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/** Starts a new session (family) for the user and returns its first refresh token. */
	public String issue(long userId) {
		Owner owner = new Owner(userId, randomValue(FAMILY_BYTES));
		String token = randomValue(TOKEN_BYTES);
		redis.opsForValue().set(FAMILY_PREFIX + owner.familyId(), hash(token), properties.refreshTokenTtl());
		redis.opsForValue().set(KEY_PREFIX + hash(token), owner.value(), properties.refreshTokenTtl());
		return token;
	}

	/**
	 * Exchanges the session's current token for a new one, atomically, so a token can be
	 * used at most once even when requests race. Empty if the token is unknown or its
	 * session has ended; a token already used ends its session.
	 */
	public Optional<Rotation> rotate(String token) {
		String presented = hash(token);
		Optional<Owner> found = owner(presented);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Owner owner = found.get();
		String next = randomValue(TOKEN_BYTES);
		long result = redis.execute(ROTATE, List.of(FAMILY_PREFIX + owner.familyId(), KEY_PREFIX + hash(next)),
				presented, hash(next), owner.value(), String.valueOf(properties.refreshTokenTtl().toSeconds()));
		if (result == 0) {
			log.warn("Refresh token used twice; ended that session of user {}", owner.userId());
		}
		return result == 1 ? Optional.of(new Rotation(owner.userId(), next)) : Optional.empty();
	}

	/** Ends the token's session, whichever of its tokens is presented. */
	public void revoke(String token) {
		String presented = hash(token);
		owner(presented).ifPresent(owner -> redis.delete(List.of(FAMILY_PREFIX + owner.familyId(), KEY_PREFIX + presented)));
	}

	private Optional<Owner> owner(String tokenHash) {
		return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + tokenHash)).flatMap(Owner::parse);
	}

	private String randomValue(int bytes) {
		byte[] value = new byte[bytes];
		random.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static String hash(String token) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available", ex);
		}
	}

}
