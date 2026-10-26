package com.roomkeeper.search;

import java.time.Instant;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Hotel info and search results are cached in Redis. Tests that need to prove a response
 * came from the cache change the database directly, which no cache eviction sees.
 */
@Import(MutableClockConfig.class)
class CachingIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcTemplate jdbc;

	private String manager;

	private String city;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void activateAHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		city = "Cache-" + UUID.randomUUID();
		hotelId = createHotel(manager, aHotel().named("Seaside Inn").inCity(city).build());
		roomId = createRoom(manager, hotelId, aRoom().ofType("Deluxe").build());
		activateHotel(manager, hotelId);
	}

	@Test
	void hotelInfoIsCachedForAboutTenMinutes() {
		info().statusCode(200);

		String key = "hotel:" + hotelId;
		assertThat(redis.hasKey(key)).isTrue();
		assertThat(redis.getExpire(key)).isBetween(590L, 600L);
	}

	@Test
	void cachedHotelInfoIsServedWithoutTheDatabase() {
		info().body("name", equalTo("Seaside Inn"));
		jdbc.update("update hotels set name = 'Changed behind the cache' where id = ?", hotelId);

		info().body("name", equalTo("Seaside Inn"));
	}

	@Test
	void editingTheHotelEvictsItsInfo() {
		info().body("name", equalTo("Seaside Inn"));

		api(manager).contentType(ContentType.JSON).body(aHotel().named("Seaside Resort").inCity(city).build())
			.when().put("/api/v1/admin/hotels/{id}", hotelId)
			.then().statusCode(200);

		info().body("name", equalTo("Seaside Resort"));
	}

	@Test
	void addingARoomTypeEvictsTheHotelsInfo() {
		info().body("rooms.type", contains("Deluxe"));

		createRoom(manager, hotelId, aRoom().ofType("Suite").build());

		info().body("rooms.type", contains("Deluxe", "Suite"));
	}

	@Test
	void searchResultsAreCachedForAboutAMinute() {
		search().body("totalHotels", equalTo(1));

		var keys = redis.keys("search:*");
		assertThat(keys).hasSize(1);
		assertThat(redis.getExpire(keys.iterator().next())).isBetween(55L, 60L);
	}

	@Test
	void cachedSearchResultsAreServedWithoutTheDatabase() {
		search().body("hotels[0].name", equalTo("Seaside Inn"));
		jdbc.update("update hotels set name = 'Changed behind the cache' where id = ?", hotelId);

		search().body("hotels[0].name", equalTo("Seaside Inn"));
	}

	@Test
	void editingARoomTypesCapacityRefreshesTheInfoButKeepsCachedSearches() {
		info().body("rooms.capacity", contains(2));
		search();

		api(manager).contentType(ContentType.JSON).body(aRoom().ofType("Deluxe").sleeping(3).build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then().statusCode(200);

		info().body("rooms.capacity", contains(3));
		// Capacity isn't part of search results or prices, so the cached search stays valid
		search();
		assertThat(redis.keys("search:*")).hasSize(1);
	}

	@Test
	void movingAHotelToAnotherCityDropsItFromTheOldCitysCachedSearches() {
		search().body("totalHotels", equalTo(1));

		api(manager).contentType(ContentType.JSON).body(aHotel().named("Seaside Inn").inCity(city + "-moved").build())
			.when().put("/api/v1/admin/hotels/{id}", hotelId)
			.then().statusCode(200);

		search().body("totalHotels", equalTo(0));
	}

	@Test
	void theSameSearchWithDefaultsSpelledOutSharesTheCacheEntry() {
		search();
		api().queryParam("city", city.toUpperCase()).queryParam("checkIn", "2026-03-12")
			.queryParam("checkOut", "2026-03-15").queryParam("rooms", 1).queryParam("page", 0).queryParam("size", 20)
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200);

		assertThat(redis.keys("search:*")).hasSize(1);
	}

	private ValidatableResponse info() {
		return api().when().get("/api/v1/hotels/{id}/info", hotelId).then().statusCode(200);
	}

	private ValidatableResponse search() {
		return api().queryParam("city", city).queryParam("checkIn", "2026-03-12").queryParam("checkOut", "2026-03-15")
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200);
	}

}
