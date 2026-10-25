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

	private final Clock clock;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
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

	static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

}
