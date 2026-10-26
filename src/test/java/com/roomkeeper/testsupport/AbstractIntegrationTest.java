package com.roomkeeper.testsupport;

import java.util.Map;

import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static io.restassured.RestAssured.given;
import static io.restassured.path.json.config.JsonPathConfig.NumberReturnType.BIG_DECIMAL;

/**
 * Base class for tests that run the full application against real Postgres and Redis,
 * using the shared {@link Containers}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = Containers.POSTGRES;

	@ServiceConnection(name = "redis")
	static final GenericContainer<?> REDIS = Containers.REDIS;

	@LocalServerPort
	private int port;

	@Autowired
	private StringRedisTemplate redis;

	@Value("${roomkeeper.admin.email}")
	private String adminEmail;

	@Value("${roomkeeper.admin.password}")
	private String adminPassword;

	private String adminToken;

	/**
	 * Redis only holds short-lived state (rate-limit buckets, refresh tokens), and every
	 * test calls from the same IP, so each test starts with an empty Redis. Postgres isn't
	 * reset; tests stay independent there by creating their own data (unique emails).
	 */
	@BeforeEach
	void resetRedis() {
		adminToken = null;
		redis.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	/**
	 * A REST Assured request against the running app, over real HTTP. Decimal numbers in
	 * responses are read as BigDecimal, so money compares exactly.
	 */
	protected RequestSpecification api() {
		return given().port(port).accept(ContentType.JSON)
			.config(RestAssuredConfig.config().jsonConfig(JsonConfig.jsonConfig().numberReturnType(BIG_DECIMAL)));
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
		return logIn(user.email(), user.password());
	}

	private String logIn(String email, String password) {
		return api().contentType(ContentType.JSON).body(Map.of("email", email, "password", password))
			.when().post("/api/v1/auth/login")
			.then().statusCode(200)
			.extract().jsonPath().getString("accessToken");
	}

	/** An access token for the admin configured in the test profile, logged in once per test. */
	protected String adminToken() {
		if (adminToken == null) {
			adminToken = logIn(adminEmail, adminPassword);
		}
		return adminToken;
	}

	/** Approves the hotel as the admin, so its manager can activate it. */
	protected void approveHotel(long hotelId) {
		api(adminToken()).when().patch("/api/v1/moderation/hotels/{id}/approve", hotelId)
			.then().statusCode(200);
	}

	/** Has the admin approve the hotel, then activates it as its manager. */
	protected void activateHotel(String managerToken, long hotelId) {
		approveHotel(hotelId);
		api(managerToken).when().patch("/api/v1/admin/hotels/{id}/activate", hotelId)
			.then().statusCode(200);
	}

	/** Signs the user up, logs them in, and returns their access token. */
	protected String signUpAndLogIn(TestUser user) {
		signUp(user);
		return logIn(user);
	}

	/** Creates a hotel as the manager the token belongs to and returns its id. */
	protected long createHotel(String managerToken, Map<String, Object> hotel) {
		return api(managerToken).contentType(ContentType.JSON).body(hotel)
			.when().post("/api/v1/admin/hotels")
			.then().statusCode(201)
			.extract().jsonPath().getLong("id");
	}

	/** Adds a room type to the hotel and returns its id. */
	protected long createRoom(String managerToken, long hotelId, Map<String, Object> room) {
		return api(managerToken).contentType(ContentType.JSON).body(room)
			.when().post("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then().statusCode(201)
			.extract().jsonPath().getLong("id");
	}

}
