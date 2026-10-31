package com.roomkeeper.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.roomkeeper.outbox.OutboxRelay;
import com.roomkeeper.outbox.OutboxStreamReaders;
import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static io.restassured.RestAssured.given;
import static io.restassured.path.json.config.JsonPathConfig.NumberReturnType.BIG_DECIMAL;
import static org.awaitility.Awaitility.await;

/**
 * Base class for tests that run the full application against real Postgres and Redis,
 * using the shared {@link Containers}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FakePaymentGatewayConfig.class)
public abstract class AbstractIntegrationTest {

	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = Containers.POSTGRES;

	@ServiceConnection(name = "redis")
	static final GenericContainer<?> REDIS = Containers.REDIS;

	@LocalServerPort
	private int port;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private JdbcTemplate database;

	@Autowired
	private Clock applicationClock;

	@Autowired
	private FakePaymentGateway paymentGateway;

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private OutboxStreamReaders outboxReaders;

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

	/**
	 * A signed-in request with a new Idempotency-Key, which requests that create a booking
	 * or a payment must carry.
	 */
	protected RequestSpecification idempotentApi(String accessToken) {
		return api(accessToken).header("Idempotency-Key", UUID.randomUUID().toString());
	}

	/**
	 * Waits for a cached response to reach Redis and returns the keys matching the pattern.
	 * The cache doesn't wait for Redis to store a response (Spring Data Redis's default with
	 * Lettuce), so the entry can land just after the response. A test that reads the entry,
	 * or changes the data behind it, waits for it first.
	 */
	protected Set<String> awaitCached(String keyPattern) {
		return await().atMost(Duration.ofSeconds(5)).until(() -> redis.keys(keyPattern), keys -> !keys.isEmpty());
	}

	/**
	 * Signs the user up and returns their id. Signup doesn't say (its answer is the same for
	 * a registered email), so the id is read from the database.
	 */
	protected long signUp(TestUser user) {
		api().contentType(ContentType.JSON).body(user.signupRequest())
			.when().post("/api/v1/auth/signup")
			.then().statusCode(202);
		return database.queryForObject("select id from users where email = ?", Long.class,
				user.email().strip().toLowerCase(Locale.ROOT));
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

	/**
	 * Creates a hotel with one room type as the manager, has the admin approve it, activates
	 * it, and returns the hotel's id.
	 */
	protected long activeHotel(String managerToken, Map<String, Object> hotel, Map<String, Object> room) {
		long hotelId = createHotel(managerToken, hotel);
		createRoom(managerToken, hotelId, room);
		activateHotel(managerToken, hotelId);
		return hotelId;
	}

	/** The id of the hotel's first room type. */
	protected long firstRoomOf(String managerToken, long hotelId) {
		return api(managerToken).when().get("/api/v1/admin/hotels/{id}/rooms", hotelId)
			.then().statusCode(200)
			.extract().jsonPath().getLong("[0].id");
	}

	/** A room type's nights from {@code from} to {@code to}, as its manager sees them. */
	protected ValidatableResponse nightsOf(String managerToken, long roomId, String from, String to) {
		return api(managerToken).queryParam("from", from).queryParam("to", to)
			.when().get("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then();
	}

	/** Sets a night's booked and held rooms directly, for tests that need sold rooms without bookings. */
	protected void sellRooms(long roomId, String night, int booked, int reserved) {
		database.update("update inventory set booked_count = ?, reserved_count = ? where room_id = ? and date = ?",
				booked, reserved, roomId, LocalDate.parse(night));
	}

	/** Saves a guest ("Asha") for the user and puts her on their booking. */
	protected void putAGuestOn(String token, long bookingId) {
		long asha = api(token).contentType(ContentType.JSON).body(Map.of("name", "Asha", "age", 30, "gender", "FEMALE"))
			.when().post("/api/v1/users/guests")
			.then().statusCode(201).extract().jsonPath().getLong("id");
		api(token).contentType(ContentType.JSON).body(Map.of("guestIds", List.of(asha)))
			.when().post("/api/v1/bookings/{id}/guests", bookingId)
			.then().statusCode(200);
	}

	/** Delivers a webhook as Stripe would, signed at the application clock's time. */
	protected ValidatableResponse deliverWebhook(String payload) {
		return api().contentType(ContentType.JSON)
			.header("Stripe-Signature", StripeWebhooks.signature(payload, applicationClock.instant()))
			.body(payload)
			.when().post("/webhook/payment")
			.then();
	}

	/** Puts a guest on the user's booking and starts paying for it; returns the checkout session's id. */
	protected String startPaying(String token, long bookingId) {
		putAGuestOn(token, bookingId);
		idempotentApi(token).when().post("/api/v1/bookings/{id}/payments", bookingId).then().statusCode(201);
		return paymentGateway.checkoutsFor(bookingId).getFirst().session().id();
	}

	/** Starts paying for the user's booking and has Stripe report it paid, which confirms it. */
	protected void pay(String token, long bookingId, String paymentIntentId) {
		String sessionId = startPaying(token, bookingId);
		deliverWebhook(StripeWebhooks.checkoutCompleted("evt_paid_" + bookingId, sessionId, paymentIntentId))
			.statusCode(200);
	}

	/**
	 * Delivers outbox events once, as the app's own threads would (they're off in tests): the
	 * relay sends unsent events to their streams, entries a failed delivery left are taken
	 * again (without waiting claim-idle), then the readers handle new entries until there are
	 * none: a read takes at most a batch, and other tests' leftover events can fill several.
	 * An event whose consumer fails is tried again on the next call.
	 */
	protected void deliverOutbox() {
		outboxRelay.relayWaiting();
		outboxReaders.claimIdle(Duration.ZERO);
		while (outboxReaders.readOnce() > 0) {
			// Next batch
		}
	}

	/** How many outbox events of the type there are about the booking or user. */
	protected long outboxEvents(String type, long aggregateId) {
		return database.queryForObject("select count(*) from outbox_events where type = ? and aggregate_id = ?",
				Long.class, type, aggregateId);
	}

	/**
	 * Inserts a guest user directly and returns their id, for tests that need many users:
	 * signing them up through the API would hit the signup rate limit.
	 */
	protected long insertGuestUser() {
		return database.queryForObject("""
				insert into users (email, password_hash, name, role, created_at)
				values (?, 'not-a-real-hash', 'Racing Guest', 'GUEST', now()) returning id""",
				Long.class, "racer-" + UUID.randomUUID() + "@example.com");
	}

	/** Adds a room type to the hotel and returns its id. */
	protected long createRoom(String managerToken, long hotelId, Map<String, Object> room) {
		return api(managerToken).contentType(ContentType.JSON).body(room)
			.when().post("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then().statusCode(201)
			.extract().jsonPath().getLong("id");
	}

}
