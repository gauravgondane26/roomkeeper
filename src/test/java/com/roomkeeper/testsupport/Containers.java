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

	/**
	 * Every cached Spring test context keeps its own connection pool, so the suite needs more
	 * connections than Postgres's default 100 allows once there are ten or so contexts (it
	 * reached 11 and CI failed with "too many clients"). fsync stays off, as Testcontainers
	 * sets it by default: test data needn't survive a crash.
	 */
	public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
		.withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200");

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
