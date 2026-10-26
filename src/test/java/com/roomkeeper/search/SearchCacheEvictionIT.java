package com.roomkeeper.search;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

/**
 * Signature test: search results are cached, but stale availability is the bug guests
 * would notice. Any change to a hotel's inventory evicts the cached searches that could
 * include it, so the next search sees the change straight away.
 */
@Import(MutableClockConfig.class)
class SearchCacheEvictionIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	StringRedisTemplate redis;

	private String manager;

	private String city;

	@BeforeEach
	void setUp() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		city = "Evict-" + UUID.randomUUID();
	}

	@Test
	void changingInventoryEvictsTheCachedSearchResultForThatHotel() {
		long hotel = activeHotel(city);
		search(city).body("totalHotels", equalTo(1));

		// The manager closes the Friday night of the stay
		api(manager).contentType(ContentType.JSON).body(Map.of("from", "2026-03-13", "to", "2026-03-13", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomOf(hotel))
			.then().statusCode(200);

		search(city).body("totalHotels", equalTo(0));
	}

	@Test
	void aNewlyActivatedHotelShowsUpInACachedSearch() {
		long first = activeHotel(city);
		search(city).body("totalHotels", equalTo(1));

		long second = activeHotel(city);

		search(city).body("hotels.id", containsInAnyOrder((int) first, (int) second));
	}

	@Test
	void searchesForOtherCitiesStayCached() {
		String otherCity = "Evict-" + UUID.randomUUID();
		activeHotel(otherCity);
		search(otherCity);
		long hotel = activeHotel(city);

		api(manager).contentType(ContentType.JSON).body(Map.of("from", "2026-03-13", "to", "2026-03-13", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomOf(hotel))
			.then().statusCode(200);

		assertThat(redis.keys("search:" + SearchQuery.cityKey(otherCity) + ":*")).hasSize(1);
	}

	@Test
	void evictionDoesNotScanRedis() {
		// SCAN walks every key in Redis (refresh tokens, rate limits, every cache), so its
		// cost grows with everything stored, not with the city being evicted
		long hotel = activeHotel(city);
		search(city);
		long scansBefore = scanCalls();

		api(manager).contentType(ContentType.JSON).body(Map.of("from", "2026-03-13", "to", "2026-03-13", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomOf(hotel))
			.then().statusCode(200);

		search(city).body("totalHotels", equalTo(0));
		assertThat(scanCalls()).isEqualTo(scansBefore);
	}

	/** How many SCAN commands Redis has run since it started. */
	private long scanCalls() {
		Properties stats = redis.execute((RedisCallback<Properties>) connection -> connection.serverCommands()
			.info("commandstats"));
		String scan = stats.getProperty("cmdstat_scan");
		return scan == null ? 0 : Long.parseLong(scan.replaceAll("^calls=(\\d+),.*$", "$1"));
	}

	private long activeHotel(String inCity) {
		long hotel = createHotel(manager, aHotel().inCity(inCity).build());
		createRoom(manager, hotel, aRoom().build());
		api(manager).when().patch("/api/v1/admin/hotels/{id}/activate", hotel).then().statusCode(200);
		return hotel;
	}

	private long roomOf(long hotel) {
		return api(manager).when().get("/api/v1/admin/hotels/{id}/rooms", hotel)
			.then().extract().jsonPath().getLong("[0].id");
	}

	/** Thursday 12 to Sunday 15 March, which includes the Friday night. */
	private ValidatableResponse search(String inCity) {
		return api().queryParam("city", inCity).queryParam("checkIn", "2026-03-12").queryParam("checkOut", "2026-03-15")
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200);
	}

}
