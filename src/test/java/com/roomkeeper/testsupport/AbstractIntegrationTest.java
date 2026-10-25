package com.roomkeeper.testsupport;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static io.restassured.RestAssured.given;

/**
 * Base class for tests that run the full application against real Postgres and Redis.
 * <p>
 * The containers are started once per test JVM and shared by every subclass, so the suite
 * pays the startup cost once. Spring only reads their connection details and never stops
 * them; Testcontainers removes them when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

	@ServiceConnection(name = "redis")
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

	static {
		Startables.deepStart(POSTGRES, REDIS).join();
	}

	@LocalServerPort
	private int port;

	/** A REST Assured request against the running app, over real HTTP. */
	protected RequestSpecification api() {
		return given().port(port).accept(ContentType.JSON);
	}

}
