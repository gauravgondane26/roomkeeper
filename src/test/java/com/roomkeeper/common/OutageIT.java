package com.roomkeeper.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.Containers;
import com.roomkeeper.testsupport.TestUser;
import com.zaxxer.hikari.HikariDataSource;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.config.JsonPathConfig.NumberReturnType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The whole app, over HTTP, while Redis or Postgres fails: paused (it stops answering, as
 * in a network partition, which is harder on the app than refusing connections), its
 * connections used up, or its connections cut. Every answer must still come within
 * {@link #PATIENCE}, with what still works working and the rest a 503 to retry. Containers
 * are paused only inside a test and always resumed; test classes run one at a time, so no
 * other test sees the outage.
 */
class OutageIT extends AbstractIntegrationTest {

	/** However broken things are, an answer comes within this. */
	private static final long PATIENCE = Duration.ofSeconds(5).toMillis();

	/**
	 * With Postgres not answering, a query already sent waits for the driver's 5 s read
	 * timeout (spring.datasource.hikari.data-source-properties.socketTimeout) first.
	 */
	private static final long DATABASE_PATIENCE = Duration.ofSeconds(10).toMillis();

	@Autowired
	DataSource dataSource;

	@Autowired
	Clock clock;

	private final String city = "Outage-" + UUID.randomUUID();

	private TestUser guestUser;

	private String guest;

	private long hotelId;

	private long roomId;

	/** A night on sale whatever today is: the hotel opens a year of them. */
	private String checkIn;

	private String checkOut;

	@BeforeEach
	void openAHotelAndSignInAGuest() {
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = activeHotel(manager, aHotel().inCity(city).build(), aRoom().build());
		roomId = firstRoomOf(manager, hotelId);
		guestUser = aUser().build();
		guest = signUpAndLogIn(guestUser);
		LocalDate night = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(10);
		checkIn = night.toString();
		checkOut = night.plusDays(1).toString();
	}

	@Test
	void withRedisNotAnsweringEverythingButSessionsAndPaymentsKeepsWorking() {
		String refreshToken = login().extract().path("refreshToken");

		whilePaused(Containers.REDIS, () -> {
			// Rate limits let the request through; the session just has no refresh token
			login().statusCode(200).body("accessToken", notNullValue()).body("refreshToken", nullValue());
			timed(api()).contentType(ContentType.JSON).body(Map.of("refreshToken", refreshToken))
				.when().post("/api/v1/auth/refresh")
				.then().time(lessThan(PATIENCE)).statusCode(503)
				.body("type", equalTo("urn:roomkeeper:problem:service-unavailable"));
			// Search and hotel info come from Postgres instead of the cache
			search().statusCode(200).body("hotels.id", contains((int) hotelId));
			timed(api()).when().get("/api/v1/hotels/{id}/info", hotelId)
				.then().time(lessThan(PATIENCE)).statusCode(200);
			// Without idempotency keys a retried booking could be made twice, so it waits
			book().statusCode(503);
			timed(api()).when().get("/readyz").then().time(lessThan(PATIENCE)).statusCode(200);
		});

		login().statusCode(200).body("refreshToken", notNullValue());
		book().statusCode(201);
	}

	@Test
	void withPostgresNotAnsweringRequestsGetA503AndTheAppIsNotReady() {
		whilePaused(Containers.POSTGRES, () -> {
			timed(api(guest)).when().get("/api/v1/users/profile")
				.then().time(lessThan(DATABASE_PATIENCE)).statusCode(503)
				.body("type", equalTo("urn:roomkeeper:problem:service-unavailable"));
			timed(api()).when().get("/readyz").then().time(lessThan(DATABASE_PATIENCE)).statusCode(503);
		});

		profile().statusCode(200);
		timed(api()).when().get("/readyz").then().statusCode(200);
	}

	@Test
	void withEveryConnectionInUseRequestsGetA503InsteadOfQueueingForever() throws Exception {
		int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
		List<Connection> held = new ArrayList<>();
		try {
			for (int i = 0; i < poolSize; i++) {
				held.add(dataSource.getConnection());
			}
			profile().statusCode(503).header("Retry-After", notNullValue());
		}
		finally {
			for (Connection connection : held) {
				connection.close();
			}
		}

		profile().statusCode(200);
	}

	@Test
	void connectionsTheDatabaseCutAreReplacedWithoutFailingRequests() throws Exception {
		profile().statusCode(200);
		try (Connection admin = DriverManager.getConnection(Containers.POSTGRES.getJdbcUrl(),
				Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword())) {
			// As a database restart or failover would: every other session ends
			admin.createStatement().executeQuery("""
					select pg_terminate_backend(pid) from pg_stat_activity
					where datname = current_database() and pid <> pg_backend_pid()""");
		}
		// The pool checks a connection that has sat idle for half a second before handing it out
		Thread.sleep(1000);

		profile().statusCode(200);
		search().statusCode(200).body("hotels.id", contains((int) hotelId));
	}

	private ValidatableResponse login() {
		return timed(api()).contentType(ContentType.JSON)
			.body(Map.of("email", guestUser.email(), "password", guestUser.password()))
			.when().post("/api/v1/auth/login")
			.then().time(lessThan(PATIENCE));
	}

	private ValidatableResponse profile() {
		return timed(api(guest)).when().get("/api/v1/users/profile").then().time(lessThan(PATIENCE));
	}

	private ValidatableResponse search() {
		return timed(api()).queryParam("city", city).queryParam("checkIn", checkIn).queryParam("checkOut", checkOut)
			.when().get("/api/v1/hotels/search")
			.then().time(lessThan(PATIENCE));
	}

	private ValidatableResponse book() {
		return timed(idempotentApi(guest)).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", checkIn, "checkOut", checkOut, "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then().time(lessThan(PATIENCE));
	}

	/** Gives up on a request that hangs, rather than waiting on it forever. */
	private static RequestSpecification timed(RequestSpecification request) {
		return request.config(RestAssuredConfig.config()
			.jsonConfig(JsonConfig.jsonConfig().numberReturnType(NumberReturnType.BIG_DECIMAL))
			.httpClient(HttpClientConfig.httpClientConfig()
				.setParam("http.socket.timeout", (int) DATABASE_PATIENCE * 2)
				.setParam("http.connection.timeout", (int) PATIENCE)));
	}

	private static void whilePaused(GenericContainer<?> container, Runnable checks) {
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
