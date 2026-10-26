package com.roomkeeper.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
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

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Guests browse without logging in. The stay searched for is Thursday 12 to Sunday 15
 * March: a Thursday night and two weekend nights.
 */
@Import(MutableClockConfig.class)
class HotelBrowseIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	private String manager;

	// Unique per test, so hotels from other tests never show up
	private String city;

	@BeforeEach
	void setUp() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		city = "Search-" + UUID.randomUUID();
	}

	@Test
	void guestsFindAvailableHotelsCheapestFirstWithoutLoggingIn() {
		long pricey = activeHotel("Grand", "7000");
		long cheap = activeHotel("Budget", "3000");

		search(Map.of())
			.statusCode(200)
			.body("hotels.id", contains((int) cheap, (int) pricey))
			.body("hotels[0].name", equalTo("Budget"))
			.body("hotels[0].city", equalTo(city))
			.body("hotels[0].amenities", contains("wifi", "pool"))
			// The cheapest night of the stay is the Thursday; the weekend nights cost 15% more
			.body("hotels[0].fromPricePerNight", equalTo(new BigDecimal("3000.00")))
			.body("page", equalTo(0))
			.body("size", equalTo(20))
			.body("totalHotels", equalTo(2))
			.body("totalPages", equalTo(1));
	}

	@Test
	void pagesThroughResults() {
		activeHotel("Budget", "3000");
		long pricey = activeHotel("Grand", "7000");

		search(Map.of("size", 1, "page", 1))
			.statusCode(200)
			.body("hotels.id", contains((int) pricey))
			.body("totalHotels", equalTo(2))
			.body("totalPages", equalTo(2));
	}

	@Test
	void aHotelClosedForOneNightOfTheStayDropsOut() {
		long hotel = activeHotel("Seaside", "4000");
		long roomId = roomOf(hotel);

		api(manager).contentType(ContentType.JSON).body(Map.of("from", "2026-03-13", "to", "2026-03-13", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then().statusCode(200);

		search(Map.of()).statusCode(200).body("hotels", empty()).body("totalHotels", equalTo(0));
	}

	@Test
	void inactiveHotelsAreNotListed() {
		long hotel = createHotel(manager, aHotel().inCity(city).build());
		createRoom(manager, hotel, aRoom().build());

		search(Map.of()).statusCode(200).body("hotels", empty());
	}

	@Test
	void rejectsAnInvalidSearch() {
		api().queryParam("checkIn", "2026-03-12").queryParam("checkOut", "2026-03-15")
			.when().get("/api/v1/hotels/search")
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:validation"))
				.body("errors.field", contains("city"));

		search(Map.of("checkOut", "2026-03-12")).statusCode(400).body("errors.field", contains("checkOut"));
		search(Map.of("checkOut", "2026-04-12")).statusCode(400).body("errors.field", contains("checkOut"));
		search(Map.of("rooms", 0)).statusCode(400).body("errors.field", contains("rooms"));
		search(Map.of("size", 51)).statusCode(400).body("errors.field", contains("size"));
		search(Map.of("page", -1)).statusCode(400).body("errors.field", contains("page"));
	}

	@Test
	void guestsSeeAnActiveHotelsDetailsAndRoomTypes() {
		long hotel = createHotel(manager, aHotel().named("Seaside Inn").inCity(city).inTimeZone("Asia/Kolkata").build());
		createRoom(manager, hotel, aRoom().ofType("Deluxe").pricedAt("4500").sleeping(2).build());
		createRoom(manager, hotel, aRoom().ofType("Suite").pricedAt("8000").sleeping(4).build());
		activate(hotel);

		api()
			.when().get("/api/v1/hotels/{id}/info", hotel)
			.then()
				.statusCode(200)
				.body("id", equalTo((int) hotel))
				.body("name", equalTo("Seaside Inn"))
				.body("city", equalTo(city))
				.body("timeZone", equalTo("Asia/Kolkata"))
				.body("amenities", contains("wifi", "pool"))
				.body("rooms.type", contains("Deluxe", "Suite"))
				.body("rooms.capacity", contains(2, 4))
				.body("rooms.basePrice", contains(new BigDecimal("4500.00"), new BigDecimal("8000.00")))
				// Internal details stay internal
				.body("$", not(hasKey("ownerId")))
				.body("rooms[0]", not(hasKey("totalCount")));
	}

	@Test
	void anInactiveOrUnknownHotelIsNotFound() {
		long inactive = createHotel(manager, aHotel().inCity(city).build());

		api().when().get("/api/v1/hotels/{id}/info", inactive)
			.then().statusCode(404).body("type", equalTo("urn:roomkeeper:problem:not-found"));
		api().when().get("/api/v1/hotels/{id}/info", Long.MAX_VALUE)
			.then().statusCode(404);
	}

	private long activeHotel(String name, String price) {
		long hotel = createHotel(manager, aHotel().named(name).inCity(city).build());
		createRoom(manager, hotel, aRoom().pricedAt(price).build());
		activate(hotel);
		return hotel;
	}

	private void activate(long hotel) {
		api(manager).when().patch("/api/v1/admin/hotels/{id}/activate", hotel).then().statusCode(200);
	}

	private long roomOf(long hotel) {
		return api(manager).when().get("/api/v1/admin/hotels/{id}/rooms", hotel)
			.then().extract().jsonPath().getLong("[0].id");
	}

	/** Searches the test's city for the stay, with any parameters overridden. */
	private ValidatableResponse search(Map<String, Object> overrides) {
		var request = api().queryParam("city", city).queryParam("checkIn", "2026-03-12");
		if (!overrides.containsKey("checkOut")) {
			request.queryParam("checkOut", "2026-03-15");
		}
		overrides.forEach(request::queryParam);
		return request.when().get("/api/v1/hotels/search").then();
	}

}
