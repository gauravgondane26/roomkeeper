package com.roomkeeper.testsupport;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for data slice tests: JPA, Flyway, and the repositories against the shared
 * Postgres container, without the web layer or Redis. Each test runs in a transaction
 * that is rolled back afterwards.
 */
@DataJpaTest
@ActiveProfiles("test")
public abstract class AbstractDataTest {

	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = Containers.POSTGRES;

}
