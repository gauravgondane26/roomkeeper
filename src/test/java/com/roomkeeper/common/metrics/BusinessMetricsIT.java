package com.roomkeeper.common.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.roomkeeper.booking.HoldExpiryJob;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.StripeWebhooks.checkoutCompleted;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business metrics count what happened, once: bookings created, holds that ran out,
 * webhook deliveries ignored as duplicates, requests a rate limit turned away, and search
 * cache hits and misses (the hit ratio's parts).
 */
@Import(MutableClockConfig.class)
class BusinessMetricsIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	MeterRegistry meters;

	@Autowired
	HoldExpiryJob expiryJob;

	@Autowired
	JdbcTemplate jdbc;

	private String city;

	private long roomId;

	private String guest;

	@BeforeEach
	void openAHotelWithOneRoom() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		city = "Metrics-" + UUID.randomUUID();
		roomId = firstRoomOf(manager,
				activeHotel(manager, aHotel().inCity(city).build(), aRoom().withTotalCount(1).build()));
		guest = signUpAndLogIn(aUser().build());
	}

	@Test
	void aBookingIsCountedOnceHowEverOftenItsRequestIsRetried() {
		double before = count("roomkeeper.bookings.initiated");
		String key = UUID.randomUUID().toString();

		hold(key).statusCode(201);
		hold(key).statusCode(201);
		// The only room is taken
		hold(UUID.randomUUID().toString()).statusCode(409);

		assertThat(count("roomkeeper.bookings.initiated") - before).isEqualTo(1);
	}

	@Test
	void everyHoldTheExpiryJobReleasesIsCounted() {
		hold(UUID.randomUUID().toString()).statusCode(201);
		double before = count("roomkeeper.holds.expired");
		long expiredBefore = expiredBookings();

		clock.advance(Duration.ofMinutes(10));
		expiryJob.run();

		// Ours, and any other test's hold that has run out by now
		long released = expiredBookings() - expiredBefore;
		assertThat(released).isPositive();
		assertThat(count("roomkeeper.holds.expired") - before).isEqualTo(released);
	}

	@Test
	void aRedeliveredWebhookIsCountedAsAnIgnoredDuplicate() {
		long bookingId = hold(UUID.randomUUID().toString()).statusCode(201).extract().jsonPath().getLong("id");
		String paid = checkoutCompleted("evt_metrics_" + bookingId, startPaying(guest, bookingId),
				"pi_metrics_" + bookingId);
		double before = count("roomkeeper.webhooks.duplicates.ignored");

		deliverWebhook(paid).statusCode(200);
		assertThat(count("roomkeeper.webhooks.duplicates.ignored") - before).isZero();

		deliverWebhook(paid).statusCode(200);
		deliverWebhook(paid).statusCode(200);
		assertThat(count("roomkeeper.webhooks.duplicates.ignored") - before).isEqualTo(2);
	}

	@Test
	void rateLimitRejectionsAreCountedByRoute() {
		double before = count("roomkeeper.rate.limit.rejections", "route", "login");

		// Setting up already used some of this minute's attempts
		int rejected = 0;
		for (int i = 0; i < 12; i++) {
			int status = api().contentType(ContentType.JSON)
				.body(Map.of("email", "nobody@example.com", "password", "wrong-password"))
				.when().post("/api/v1/auth/login")
				.then().extract().statusCode();
			if (status == 429) {
				rejected++;
			}
		}

		assertThat(rejected).isPositive();
		assertThat(count("roomkeeper.rate.limit.rejections", "route", "login") - before).isEqualTo(rejected);
	}

	@Test
	void searchCacheMissesAndHitsAreCounted() {
		double misses = cacheGets("search", "miss");
		double hits = cacheGets("search", "hit");

		search().statusCode(200);
		awaitCached("search:*");
		search().statusCode(200);

		assertThat(cacheGets("search", "miss") - misses).isEqualTo(1);
		assertThat(cacheGets("search", "hit") - hits).isEqualTo(1);
	}

	/** A hold on the only room for Tuesday and Wednesday nights, sent with this Idempotency-Key. */
	private ValidatableResponse hold(String idempotencyKey) {
		return api(guest).header("Idempotency-Key", idempotencyKey).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then();
	}

	private ValidatableResponse search() {
		return api().queryParam("city", city).queryParam("checkIn", "2026-03-10").queryParam("checkOut", "2026-03-12")
			.when().get("/api/v1/hotels/search")
			.then();
	}

	private long expiredBookings() {
		return jdbc.queryForObject("select count(*) from bookings where status = 'EXPIRED'", Long.class);
	}

	/** The counter's total so far; 0 before anything was counted. */
	private double count(String name, String... tags) {
		Counter counter = meters.find(name).tags(tags).counter();
		return counter == null ? 0 : counter.count();
	}

	private double cacheGets(String cache, String result) {
		FunctionCounter gets = meters.find("cache.gets").tags("cache", cache, "result", result).functionCounter();
		return gets == null ? 0 : gets.count();
	}

}
