package com.roomkeeper.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	/** Emails are stored lower-cased, so callers must pass a lower-cased email. */
	Optional<User> findByEmail(String email);

}
