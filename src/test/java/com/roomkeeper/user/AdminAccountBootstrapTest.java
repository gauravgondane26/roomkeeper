package com.roomkeeper.user;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The first admin comes from configuration, and an existing account is never promoted. */
class AdminAccountBootstrapTest {

	private final UserRepository users = mock(UserRepository.class);

	private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

	private final Clock clock = Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC);

	@Test
	void createsTheConfiguredAdminWhenMissing() {
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());

		bootstrap(" Admin@Example.com ", "a-long-admin-password").ensureAdmin();

		var saved = ArgumentCaptor.forClass(User.class);
		verify(users).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
		assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
		assertThat(passwordEncoder.matches("a-long-admin-password", saved.getValue().getPasswordHash())).isTrue();
	}

	@Test
	void leavesAnExistingAdminAlone() {
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user(Role.ADMIN)));

		bootstrap("admin@example.com", "a-long-admin-password").ensureAdmin();

		verify(users, never()).saveAndFlush(any());
	}

	@Test
	void anotherInstanceCreatingItFirstIsFine() {
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());
		when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("users_email_lower_key"));

		assertThatNoException().isThrownBy(() -> bootstrap("admin@example.com", "a-long-admin-password").ensureAdmin());
	}

	@Test
	void neverPromotesAnAccountThatAlreadyExists() {
		// Without email verification, anyone could have signed up with the admin's address
		when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user(Role.GUEST)));

		bootstrap("admin@example.com", "a-long-admin-password").ensureAdmin();

		verify(users, never()).saveAndFlush(any());
	}

	@Test
	void doesNothingWhenNoAdminIsConfigured() {
		bootstrap("", "").ensureAdmin();
		bootstrap(null, null).ensureAdmin();

		verifyNoInteractions(users);
	}

	@Test
	void refusesAnUnusableConfiguredPassword() {
		assertThatIllegalStateException().isThrownBy(() -> bootstrap("admin@example.com", "short").ensureAdmin());
		assertThatIllegalStateException().isThrownBy(() -> bootstrap("admin@example.com", "é".repeat(40)).ensureAdmin());
		assertThatIllegalStateException().isThrownBy(() -> bootstrap("admin@example.com", null).ensureAdmin());
	}

	private AdminAccountBootstrap bootstrap(String email, String password) {
		return new AdminAccountBootstrap(users, passwordEncoder, new AdminProperties(email, password), clock);
	}

	private User user(Role role) {
		return new User("admin@example.com", "{noop}x", "Someone", role, clock.instant());
	}

}
