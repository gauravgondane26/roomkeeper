package com.roomkeeper.user;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the configured admin account at startup if it doesn't exist yet. Nobody can
 * choose the admin role at signup, so this is the only way an admin comes to be.
 * <p>
 * An existing account with the admin's email is never promoted: there's no email
 * verification, so anyone could have signed up with that address first.
 */
@Component
public class AdminAccountBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminAccountBootstrap.class);

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final AdminProperties properties;

	private final Clock clock;

	public AdminAccountBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AdminProperties properties,
			Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureAdmin();
	}

	void ensureAdmin() {
		if (properties.email() == null || properties.email().isBlank()) {
			return;
		}
		String password = properties.password();
		if (!PasswordPolicy.isAcceptable(password)) {
			throw new IllegalStateException("roomkeeper.admin.password must be " + PasswordPolicy.MIN_CHARACTERS
					+ " characters to " + PasswordPolicy.MAX_UTF8_BYTES + " bytes");
		}
		String email = Emails.normalize(properties.email());
		users.findByEmail(email).ifPresentOrElse(existing -> {
			if (existing.getRole() != Role.ADMIN) {
				log.error("An account with the admin email {} already exists and is not an admin; it was not promoted. "
						+ "Configure a different admin email.", email);
			}
		}, () -> {
			try {
				users.saveAndFlush(
						new User(email, passwordEncoder.encode(password), "Administrator", Role.ADMIN, clock.instant()));
				log.info("Created the admin account {}", email);
			}
			catch (DataIntegrityViolationException ex) {
				// Another instance starting at the same time created it first
				log.info("The admin account {} was created by another instance", email);
			}
		});
	}

}
