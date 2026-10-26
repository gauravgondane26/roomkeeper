package com.roomkeeper.inventory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Changing a room type's total in room admin flows into its inventory: every future night
 * follows the new total, except nights whose total the manager set by hand.
 */
@Import(MutableClockConfig.class)
class RoomTotalChangeIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	private TestUser managerUser;

	private String manager;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void activateAHotelWithFiveDeluxeRooms() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		managerUser = aUser().asHotelManager().build();
		manager = signUpAndLogIn(managerUser);
		hotelId = createHotel(manager, aHotel().build());
		roomId = createRoom(manager, hotelId, aRoom().withTotalCount(5).build());
		api(manager).when().patch("/api/v1/admin/hotels/{id}/activate", hotelId).then().statusCode(200);
	}

	@Test
	void everyFutureNightFollowsTheNewTotal() {
		changeRoomTotal(8).statusCode(200);

		nights("2026-03-10", "2026-03-12").body("totalCount", contains(8, 8, 8));
		nights("2027-03-09", "2027-03-09").body("totalCount", contains(8));
	}

	@Test
	void nightsWithAHandSetTotalKeepIt() {
		api(manager).contentType(ContentType.JSON).body(Map.of("from", "2026-03-15", "to", "2026-03-15", "totalCount", 2))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then().statusCode(200);
		nights("2026-03-14", "2026-03-16").body("totalOverridden", contains(false, true, false));

		changeRoomTotal(8).statusCode(200);

		nights("2026-03-14", "2026-03-16").body("totalCount", contains(8, 2, 8));
	}

	@Test
	void pastNightsKeepTheirTotal() {
		clock.advance(Duration.ofDays(2));
		manager = logIn(managerUser);

		changeRoomTotal(8).statusCode(200);

		nights("2026-03-10", "2026-03-13").body("totalCount", contains(5, 5, 8, 8));
	}

	@Test
	void rejectsATotalBelowWhatIsAlreadyBookedOrHeld() {
		sell("2026-03-15", 3, 1);

		changeRoomTotal(3)
			.statusCode(409)
			.body("type", equalTo("urn:roomkeeper:problem:total-below-sold"))
			.body("nights", contains("2026-03-15"));

		// Nothing changed: not the room, not any night
		api(manager)
			.when().get("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then().body("totalCount", equalTo(5));
		nights("2026-03-14", "2026-03-16").body("totalCount", contains(5, 5, 5));
	}

	@Test
	void editingOtherRoomDetailsLeavesHandSetTotalsAlone() {
		api(manager).contentType(ContentType.JSON).body(Map.of("from", "2026-03-15", "to", "2026-03-15", "totalCount", 2))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then().statusCode(200);

		api(manager).contentType(ContentType.JSON).body(aRoom().pricedAt("5000").withTotalCount(5).build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then().statusCode(200);

		nights("2026-03-14", "2026-03-16").body("totalCount", contains(5, 2, 5));
	}

	private ValidatableResponse changeRoomTotal(int totalCount) {
		return api(manager).contentType(ContentType.JSON).body(aRoom().withTotalCount(totalCount).build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId)
			.then();
	}

	/** Stands in for bookings (week 5): marks rooms on a night as booked and held. */
	private void sell(String night, int booked, int reserved) {
		jdbc.update("update inventory set booked_count = ?, reserved_count = ? where room_id = ? and date = ?", booked,
				reserved, roomId, LocalDate.parse(night));
	}

	private ValidatableResponse nights(String from, String to) {
		return api(manager).queryParam("from", from).queryParam("to", to)
			.when().get("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then().statusCode(200);
	}

}
