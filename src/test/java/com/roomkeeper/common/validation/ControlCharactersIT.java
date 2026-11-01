package com.roomkeeper.common.validation;

import java.util.List;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Names people read (hotels, cities, amenities, room types, users, guests) can't hold
 * control characters such as line breaks: they end up in log lines and email subjects,
 * where a line break would start a forged log line or an email header.
 */
class ControlCharactersIT extends AbstractIntegrationTest {

	private static final String VALIDATION = "urn:roomkeeper:problem:validation";

	private String manager;

	@BeforeEach
	void signInAManager() {
		manager = signUpAndLogIn(aUser().asHotelManager().build());
	}

	@Test
	void hotelNamesCitiesAndAmenitiesRefuseThem() {
		postHotel(Map.of("name", "Seaside Inn\n2026-11-01 ERROR forged", "city", "Goa", "timeZone", "UTC"), "name");
		postHotel(Map.of("name", "Seaside Inn", "city", "Goa\r", "timeZone", "UTC"), "city");
		postHotel(Map.of("name", "Seaside Inn", "city", "Goa", "timeZone", "UTC", "amenities", List.of("wifi\t")),
				"amenities[0]");
	}

	@Test
	void roomTypesRefuseThem() {
		long hotelId = createHotel(manager, aHotel().build());
		var room = new java.util.HashMap<>(aRoom().build());
		room.put("type", "Deluxe\u0000");

		api(manager).contentType(ContentType.JSON).body(room)
			.when().post("/api/v1/admin/hotels/{id}/rooms", hotelId)
			.then().statusCode(400).body("type", equalTo(VALIDATION)).body("errors.field", contains("type"));
	}

	@Test
	void userAndGuestNamesRefuseThem() {
		var signup = new java.util.HashMap<>(aUser().build().signupRequest());
		signup.put("name", "Asha\nAdmin");
		api().contentType(ContentType.JSON).body(signup)
			.when().post("/api/v1/auth/signup")
			.then().statusCode(400).body("errors.field", contains("name"));

		String guest = signUpAndLogIn(aUser().build());
		api(guest).contentType(ContentType.JSON).body(Map.of("name", "Asha\r\nX"))
			.when().patch("/api/v1/users/profile")
			.then().statusCode(400).body("errors.field", contains("name"));
		api(guest).contentType(ContentType.JSON).body(Map.of("name", "Asha\n", "age", 30, "gender", "FEMALE"))
			.when().post("/api/v1/users/guests")
			.then().statusCode(400).body("errors.field", contains("name"));
	}

	@Test
	void publicSearchRefusesThemInTheCity() {
		// A NUL would otherwise reach Postgres, which refuses it in text: a 500 for anyone
		api().queryParam("city", "Goa\u0000").queryParam("checkIn", "2026-12-01").queryParam("checkOut", "2026-12-02")
			.when().get("/api/v1/hotels/search")
			.then().statusCode(400).body("type", equalTo(VALIDATION));
	}

	@Test
	void accentsSymbolsAndEmojiAreFine() {
		api(manager).contentType(ContentType.JSON)
			.body(Map.of("name", "Hôtel Été ☀ & Spa", "city", "São Paulo", "timeZone", "UTC", "amenities",
					List.of("café", "piscine 🏊")))
			.when().post("/api/v1/admin/hotels")
			.then().statusCode(201);
	}

	private void postHotel(Map<String, Object> hotel, String field) {
		api(manager).contentType(ContentType.JSON).body(hotel)
			.when().post("/api/v1/admin/hotels")
			.then().statusCode(400).body("type", equalTo(VALIDATION)).body("errors.field", contains(field));
	}

}
