package com.roomkeeper.testsupport;

import org.testcontainers.DockerClientFactory;
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

	/**
	 * Runs the checks with the container paused: it stops answering, as in a network
	 * partition, then is always resumed. Test classes run one at a time, so no other test
	 * sees the outage.
	 */
	public static void whilePaused(GenericContainer<?> container, Runnable checks) {
		var docker = DockerClientFactory.instance().client();
		docker.pauseContainerCmd(container.getContainerId()).exec();
		try {
			checks.run();
		}
		finally {
			docker.unpauseContainerCmd(container.getContainerId()).exec();
		}
	}

}
