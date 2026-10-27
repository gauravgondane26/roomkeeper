package com.roomkeeper.search;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Bookings change what search shows: each night's cheapest price, and which hotels have
 * rooms. Both follow a booking after it commits, off the request thread, so the booking
 * doesn't hold its inventory locks while prices are recomputed. Cached searches are only
 * evicted when a night sells out or reopens; smaller changes wait out the 60 s cache.
 */
@Import(MutableClockConfig.class)
class BookingSearchEffectsIT extends AbstractIntegrationTest {

	private static final String TUESDAY = "2026-03-10";

	private static final String THURSDAY = "2026-03-12";

	private static final Duration AFTER_COMMIT = Duration.ofSeconds(10);

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	DataSource dataSource;

	private String manager;

	private String guest;

	private String city;

	@BeforeEach
	void setUp() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		city = "Sales-" + UUID.randomUUID();
	}

	@Test
	void aHoldRepricesTheNightsItTakes() {
		long hotel = activeHotel(5);
		long room = firstRoomOf(manager, hotel);
		sellRooms(room, TUESDAY, 3, 0);

		hold(room, 1);

		// 4 of 5 rooms sold: the occupancy rule adds 20%
		await().atMost(AFTER_COMMIT).untilAsserted(() -> assertThat(minPrices(hotel)).containsExactly(
				new BigDecimal("4800.00"), new BigDecimal("4000.00")));
	}

	@Test
	void aHoldDoesntWaitForTheRecompute() throws Exception {
		long hotel = activeHotel(5);
		long room = firstRoomOf(manager, hotel);
		try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
			// Another transaction holds the hotel's recompute lock, as a slow recompute would
			other.setAutoCommit(false);
			statement.execute("select pg_advisory_xact_lock(" + hotel + ")");

			CompletableFuture.runAsync(() -> hold(room, 1)).get(5, TimeUnit.SECONDS);

			other.rollback();
		}
		await().atMost(AFTER_COMMIT).untilAsserted(() -> assertThat(minPrices(hotel)).hasSize(2));
	}

	@Test
	void holdingTheLastRoomTakesTheNightsOutOfTheCheapestPrices() {
		long hotel = activeHotel(1);

		hold(firstRoomOf(manager, hotel), 1);

		await().atMost(AFTER_COMMIT).untilAsserted(() -> assertThat(minPrices(hotel)).isEmpty());
	}

	@Test
	void aHoldThatLeavesRoomsKeepsTheCitysCachedSearches() {
		long hotel = activeHotel(5);
		long room = firstRoomOf(manager, hotel);
		sellRooms(room, TUESDAY, 3, 0);
		search().body("totalHotels", equalTo(1));
		String version = searchVersion();

		hold(room, 1);

		await().atMost(AFTER_COMMIT)
			.untilAsserted(() -> assertThat(minPrices(hotel)).first().isEqualTo(new BigDecimal("4800.00")));
		assertThat(searchVersion()).isEqualTo(version);
		assertThat(awaitCached("search:" + SearchQuery.cityKey(city) + ":*")).hasSize(1);
	}

	@Test
	void sellingOutANightEvictsTheCitysCachedSearches() {
		long hotel = activeHotel(1);
		search().body("totalHotels", equalTo(1));

		hold(firstRoomOf(manager, hotel), 1);

		await().atMost(AFTER_COMMIT).untilAsserted(() -> search().body("totalHotels", equalTo(0)));
	}

	private long activeHotel(int rooms) {
		return activeHotel(manager, aHotel().inCity(city).build(),
				aRoom().pricedAt("4000").withTotalCount(rooms).build());
	}

	/** Holds rooms for Tuesday and Wednesday nights. */
	private void hold(long roomId, int rooms) {
		api(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", TUESDAY, "checkOut", THURSDAY, "rooms", rooms))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201);
	}

	private ValidatableResponse search() {
		return api().queryParam("city", city).queryParam("checkIn", TUESDAY).queryParam("checkOut", THURSDAY)
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200);
	}

	/** Tuesday's and Wednesday's cheapest prices, for the nights that have one. */
	private List<BigDecimal> minPrices(long hotelId) {
		return jdbc.queryForList("""
				select min_price from hotel_min_price
				where hotel_id = ? and date between '2026-03-10' and '2026-03-11' order by date""",
				BigDecimal.class, hotelId);
	}

	private String searchVersion() {
		return redis.opsForValue().get("search-version:" + SearchQuery.cityKey(city));
	}

}
