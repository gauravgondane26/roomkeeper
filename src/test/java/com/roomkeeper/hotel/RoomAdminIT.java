package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

class RoomAdminIT extends AbstractIntegrationTest {

	private String manager;

	private long hotelId;

	@BeforeEach
	void createAHotel() {
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = createHotel(manager, aHotel().build());
	}

	@Test
	void ownerAddsARoomType() {
		var response = api(manager).contentType(ContentType.JSON)
			.body(aRoom().ofType(" Deluxe King ").pricedAt("4500").sleeping(2).withTotalCount(8).build())
			.when().post("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.statusCode(201)
				.body("hotelId", equalTo((int) hotelId))
				.body("type", equalTo("Deluxe King"))
				.body("basePrice", equalTo(new BigDecimal("4500.00")))
				.body("capacity", equalTo(2))
				.body("totalCount", equalTo(8))
				.extract();

		long id = response.jsonPath().getLong("id");
		assertThat(response.header("Location")).endsWith("/api/v1/admin/hotels/" + hotelId + "/rooms/" + id);
	}

	@Test
	void rejectsAnInvalidRoomType() {
		api(manager).contentType(ContentType.JSON)
			.body(Map.of("type", " ", "basePrice", 0, "capacity", 0, "totalCount", -1))
			.when().post("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:validation"))
				.body("errors.field", containsInAnyOrder("type", "basePrice", "capacity", "totalCount"));
	}

	@Test
	void rejectsAPriceWithMoreThanTwoDecimals() {
		api(manager).contentType(ContentType.JSON).body(aRoom().pricedAt("4500.001").build())
			.when().post("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.statusCode(400)
				.body("errors.field", contains("basePrice"));
	}

	@Test
	void roomTypeNamesAreUniqueWithinAHotelIgnoringCase() {
		createRoom(manager, hotelId, aRoom().ofType("Deluxe").build());

		api(manager).contentType(ContentType.JSON).body(aRoom().ofType("DELUXE").build())
			.when().post("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.statusCode(409)
				.body("type", equalTo("urn:roomkeeper:problem:room-type-exists"));

		long otherHotel = createHotel(manager, aHotel().named("Hillside").build());
		createRoom(manager, otherHotel, aRoom().ofType("Deluxe").build());
	}

	@Test
	void ownerListsReadsUpdatesAndDeletesRooms() {
		long deluxe = createRoom(manager, hotelId, aRoom().ofType("Deluxe").build());
		createRoom(manager, hotelId, aRoom().ofType("Suite").build());

		api(manager)
			.when().get("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.statusCode(200)
				.body("type", contains("Deluxe", "Suite"));

		api(manager).contentType(ContentType.JSON)
			.body(aRoom().ofType("Deluxe Sea View").pricedAt("5200.50").sleeping(3).withTotalCount(4).build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, deluxe)
			.then()
				.statusCode(200)
				.body("type", equalTo("Deluxe Sea View"))
				.body("basePrice", equalTo(new BigDecimal("5200.50")))
				.body("capacity", equalTo(3))
				.body("totalCount", equalTo(4));

		api(manager)
			.when().get("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, deluxe)
			.then()
				.statusCode(200)
				.body("type", equalTo("Deluxe Sea View"));

		api(manager)
			.when().delete("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, deluxe)
			.then()
				.statusCode(204);

		api(manager)
			.when().get("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.body("type", contains("Suite"));
	}

	@Test
	void renamingARoomToAnotherTypesNameIsAConflict() {
		createRoom(manager, hotelId, aRoom().ofType("Deluxe").build());
		long suite = createRoom(manager, hotelId, aRoom().ofType("Suite").build());

		api(manager).contentType(ContentType.JSON).body(aRoom().ofType("deluxe").build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, suite)
			.then()
				.statusCode(409)
				.body("type", equalTo("urn:roomkeeper:problem:room-type-exists"));

		// Keeping its own name (in any case) is fine
		api(manager).contentType(ContentType.JSON).body(aRoom().ofType("SUITE").build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, suite)
			.then()
				.statusCode(200);
	}

	@Test
	void anotherManagerCannotSeeOrChangeTheRooms() {
		long roomId = createRoom(manager, hotelId, aRoom().build());
		String otherManager = signUpAndLogIn(aUser().asHotelManager().build());

		api(otherManager)
			.when().get("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.statusCode(403)
				.body("type", equalTo("urn:roomkeeper:problem:forbidden"));
		api(otherManager).contentType(ContentType.JSON).body(aRoom().ofType("Hijack").build())
			.when().post("/api/v1/admin/hotels/{hotelId}/rooms", hotelId)
			.then()
				.statusCode(403);
		api(otherManager).contentType(ContentType.JSON).body(aRoom().build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then()
				.statusCode(403);
		api(otherManager)
			.when().delete("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then()
				.statusCode(403);
	}

	@Test
	void aRoomIsOnlyReachableThroughItsOwnHotel() {
		long roomId = createRoom(manager, hotelId, aRoom().build());
		long otherHotel = createHotel(manager, aHotel().named("Hillside").build());

		api(manager)
			.when().get("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", otherHotel, roomId)
			.then()
				.statusCode(404)
				.body("type", equalTo("urn:roomkeeper:problem:not-found"));
	}

	@Test
	void deletingAHotelDeletesItsRooms() {
		long roomId = createRoom(manager, hotelId, aRoom().build());

		api(manager)
			.when().delete("/api/v1/admin/hotels/{hotelId}", hotelId)
			.then()
				.statusCode(204);

		api(manager)
			.when().get("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then()
				.statusCode(404);
	}

}
