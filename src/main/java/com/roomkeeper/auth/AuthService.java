package com.roomkeeper.auth;

import java.time.Clock;
import java.util.Locale;

import com.roomkeeper.user.Role;
import com.roomkeeper.user.User;
import com.roomkeeper.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AccessTokenService accessTokens;

	private final Clock clock;

	/**
	 * Checked against when the email is unknown, so a login for a missing account takes as
	 * long as one with a wrong password and response times don't reveal which emails exist.
	 */
	private final String dummyPasswordHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
			Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.accessTokens = accessTokens;
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
	public AccessToken login(LoginRequest request) {
		User user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
		if (user == null) {
			passwordEncoder.matches(request.password(), dummyPasswordHash);
			throw new InvalidCredentialsException();
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		return accessTokens.issue(user.getId(), user.getRole());
	}

	static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

}
