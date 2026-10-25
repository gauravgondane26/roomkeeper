package com.roomkeeper.auth;

import java.time.Clock;
import java.util.Locale;
import java.util.function.Supplier;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.user.Role;
import com.roomkeeper.user.User;
import com.roomkeeper.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenService accessTokens;

	private final RefreshTokenStore refreshTokens;

	private final AuthProperties properties;

	private final Clock clock;

	/**
	 * Checked against when the email is unknown, so a login for a missing account takes as
	 * long as one with a wrong password and response times don't reveal which emails exist.
	 */
	private final String dummyPasswordHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
			RefreshTokenStore refreshTokens, AuthProperties properties, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
		this.properties = properties;
		this.clock = clock;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-unknown-emails");
	}

	@Transactional
	public User signup(SignupRequest request) {
		String email = normalizeEmail(request.email());
		if (users.existsByEmail(email)) {
			throw new EmailAlreadyRegisteredException();
		}
		Role role = request.role() != null ? request.role() : Role.GUEST;
		User user = new User(email, passwordEncoder.encode(request.password()), request.name().strip(), role,
				clock.instant());
		try {
			return users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// Two signups with the same email raced past the check; the unique index caught it
			throw new EmailAlreadyRegisteredException();
		}
	}

	@Transactional(readOnly = true)
	public TokenResponse login(LoginRequest request) {
		User user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
		if (user == null) {
			passwordEncoder.matches(request.password(), dummyPasswordHash);
			throw new InvalidCredentialsException();
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		return issueTokens(user);
	}

	/** Exchanges a refresh token for a new pair. The old refresh token stops working. */
	@Transactional(readOnly = true)
	public TokenResponse refresh(RefreshRequest request) {
		long userId = withRedis(() -> refreshTokens.consume(request.refreshToken()))
			.orElseThrow(InvalidRefreshTokenException::new);
		User user = users.findById(userId).orElseThrow(InvalidRefreshTokenException::new);
		return issueTokens(user);
	}

	/** Revokes the refresh token. Unknown tokens are ignored, so this never reveals anything. */
	public void logout(RefreshRequest request) {
		withRedis(() -> {
			refreshTokens.revoke(request.refreshToken());
			return null;
		});
	}

	private TokenResponse issueTokens(User user) {
		AccessToken accessToken = accessTokens.issue(user.getId(), user.getRole());
		String refreshToken;
		try {
			refreshToken = refreshTokens.issue(user.getId());
		}
		catch (DataAccessException ex) {
			// Redis is down: still log the user in, just without a refresh token, so they
			// sign in again when the access token expires instead of not at all
			log.warn("Could not store refresh token; issuing access token only", ex);
			refreshToken = null;
		}
		return TokenResponse.of(accessToken, refreshToken, properties);
	}

	private static <T> T withRedis(Supplier<T> action) {
		try {
			return action.get();
		}
		catch (DataAccessException ex) {
			log.warn("Refresh token store unavailable", ex);
			throw new ApiException(ProblemType.SERVICE_UNAVAILABLE,
					"Sessions can't be refreshed right now; log in again");
		}
	}

	static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

}
