package com.roomkeeper.auth;

import java.time.Clock;
import java.util.function.Supplier;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.outbox.AccountCreated;
import com.roomkeeper.outbox.OutboxWriter;
import com.roomkeeper.outbox.SignupForExistingAccount;
import com.roomkeeper.user.Emails;
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
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private final UserRepository users;

	private final OutboxWriter outbox;

	private final TransactionTemplate transaction;

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

	public AuthService(UserRepository users, OutboxWriter outbox, TransactionTemplate transaction,
			PasswordEncoder passwordEncoder, AccessTokenService accessTokens, RefreshTokenStore refreshTokens,
			AuthProperties properties, Clock clock) {
		this.users = users;
		this.outbox = outbox;
		this.transaction = transaction;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
		this.refreshTokens = refreshTokens;
		this.properties = properties;
		this.clock = clock;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-unknown-emails");
	}

	/**
	 * Creates the account if the email is new, and the same from the outside if it isn't:
	 * the caller always gets the same answer, so signup can't tell anyone which emails are
	 * registered. The password is hashed either way, so the time taken doesn't tell either.
	 * A new account's owner is welcomed by email; a registered address's owner is told
	 * someone tried to sign up with it.
	 */
	public void signup(SignupRequest request) {
		String email = Emails.normalize(request.email());
		String passwordHash = passwordEncoder.encode(request.password());
		Role role = request.role() != null ? request.role().role() : Role.GUEST;
		try {
			transaction.executeWithoutResult(status -> {
				User owner = users.findByEmail(email).orElse(null);
				if (owner != null) {
					outbox.write(new SignupForExistingAccount(owner.getId()));
					return;
				}
				User user = users.saveAndFlush(
						new User(email, passwordHash, request.name().strip(), role, clock.instant()));
				outbox.write(new AccountCreated(user.getId()));
			});
		}
		catch (DataIntegrityViolationException ex) {
			// Signups with this email raced past the check and another won; its owner is told of this one
			transaction.executeWithoutResult(status -> users.findByEmail(email)
				.ifPresent(owner -> outbox.write(new SignupForExistingAccount(owner.getId()))));
		}
	}

	@Transactional(readOnly = true)
	public TokenResponse login(LoginRequest request) {
		User user = users.findByEmail(Emails.normalize(request.email())).orElse(null);
		if (user == null) {
			passwordEncoder.matches(request.password(), dummyPasswordHash);
			throw new InvalidCredentialsException();
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		return issueTokens(user);
	}

	/**
	 * Exchanges a refresh token for a new pair in the same session. The old refresh token
	 * stops working, and presenting it again ends the session.
	 */
	@Transactional(readOnly = true)
	public TokenResponse refresh(RefreshRequest request) {
		RefreshTokenStore.Rotation rotation = withRedis(() -> refreshTokens.rotate(request.refreshToken()))
			.orElseThrow(InvalidRefreshTokenException::new);
		User user = users.findById(rotation.userId()).orElseThrow(InvalidRefreshTokenException::new);
		return TokenResponse.of(accessTokens.issue(user.getId(), user.getRole()), rotation.refreshToken(), properties);
	}

	/** Ends the refresh token's session. Unknown tokens are ignored, so this never reveals anything. */
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

}
