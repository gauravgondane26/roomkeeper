package com.roomkeeper.testsupport;

import java.util.Map;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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

	@Autowired
	private StringRedisTemplate redis;

	/**
	 * Redis only holds short-lived state (rate-limit buckets, refresh tokens), and every
	 * test calls from the same IP, so each test starts with an empty Redis. Postgres isn't
	 * reset; tests stay independent there by creating their own data (unique emails).
	 */
	@BeforeEach
	void resetRedis() {
		redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	/** A REST Assured request against the running app, over real HTTP. */
	protected RequestSpecification api() {
		return given().port(port).accept(ContentType.JSON);
	}

	/** A request that sends the given access token as a bearer token. */
	protected RequestSpecification api(String accessToken) {
		return api().header("Authorization", "Bearer " + accessToken);
	}

	/** Signs the user up and returns their id. */
	protected long signUp(TestUser user) {
		return api().contentType(ContentType.JSON).body(user.signupRequest())
			.when().post("/api/v1/auth/signup")
			.then().statusCode(201)
			.extract().jsonPath().getLong("id");
	}

	/** Logs the user in and returns their access token. */
	protected String logIn(TestUser user) {
		return api().contentType(ContentType.JSON).body(Map.of("email", user.email(), "password", user.password()))
			.when().post("/api/v1/auth/login")
			.then().statusCode(200)
			.extract().jsonPath().getString("accessToken");
	}

	/** Signs the user up, logs them in, and returns their access token. */
	protected String signUpAndLogIn(TestUser user) {
		signUp(user);
		return logIn(user);
	}

}
