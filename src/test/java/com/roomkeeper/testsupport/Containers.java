package com.roomkeeper.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Postgres and Redis containers, started once per test JVM and shared by every test
 * base class, so the suite pays the startup cost once. Spring only reads their connection
 * details and never stops them; Testcontainers removes them when the JVM exits.
 */
public final class Containers {

	public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

	public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

	static {
		Startables.deepStart(POSTGRES, REDIS).join();
	}

	private Containers() {
	}

}
