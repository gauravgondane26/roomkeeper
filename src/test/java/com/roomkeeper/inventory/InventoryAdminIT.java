package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@Import(MutableClockConfig.class)
class InventoryAdminIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	private String manager;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void activateAHotelWithOneRoomType() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = createHotel(manager, aHotel().build());
		roomId = createRoom(manager, hotelId, aRoom().withTotalCount(5).build());
		activateHotel(manager, hotelId);
	}

	@Test
	void showsEachNightInTheRange() {
		nights("2026-03-10", "2026-03-12")
			.statusCode(200)
			.body("date", contains("2026-03-10", "2026-03-11", "2026-03-12"))
			.body("totalCount", everyItem(equalTo(5)))
			.body("bookedCount", everyItem(equalTo(0)))
			.body("reservedCount", everyItem(equalTo(0)))
			.body("available", everyItem(equalTo(5)))
			.body("surgeFactor", everyItem(equalTo(new BigDecimal("1.00"))))
			.body("closed", everyItem(equalTo(false)));
	}

	@Test
	void showsTheNext30NightsByDefault() {
		api(manager)
			.when().get("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then()
				.statusCode(200)
				.body("$", hasSize(30))
				.body("[0].date", equalTo("2026-03-10"))
				.body("[29].date", equalTo("2026-04-08"));
	}

	@Test
	void closesAndReopensNights() {
		update(Map.of("from", "2026-03-12", "to", "2026-03-13", "closed", true))
			.statusCode(200)
			.body("date", contains("2026-03-12", "2026-03-13"))
			.body("closed", everyItem(equalTo(true)));

		nights("2026-03-11", "2026-03-14")
			.body("closed", contains(false, true, true, false));

		update(Map.of("from", "2026-03-13", "to", "2026-03-13", "closed", false)).statusCode(200);

		nights("2026-03-11", "2026-03-14")
			.body("closed", contains(false, true, false, false));
	}

	@Test
	void changesTheSurgeFactorOverARange() {
		update(Map.of("from", "2026-12-24", "to", "2026-12-26", "surgeFactor", 1.5)).statusCode(200);

		nights("2026-12-23", "2026-12-27")
			.body("surgeFactor", contains(new BigDecimal("1.00"), new BigDecimal("1.50"), new BigDecimal("1.50"),
					new BigDecimal("1.50"), new BigDecimal("1.00")));
	}

	@Test
	void changesTheTotalOverARange() {
		update(Map.of("from", "2026-03-11", "to", "2026-03-12", "totalCount", 8))
			.statusCode(200)
			.body("totalCount", contains(8, 8))
			.body("available", contains(8, 8));

		nights("2026-03-10", "2026-03-13")
			.body("totalCount", contains(5, 8, 8, 5));
	}

	@Test
	void showsBookedAndHeldRooms() {
		sell("2026-03-15", 2, 1);

		nights("2026-03-15", "2026-03-15")
			.body("[0].bookedCount", equalTo(2))
			.body("[0].reservedCount", equalTo(1))
			.body("[0].available", equalTo(2));
	}

	@Test
	void rejectsLoweringATotalBelowWhatIsAlreadyBookedOrHeld() {
		sell("2026-03-15", 2, 1);
		sell("2026-03-16", 3, 0);

		update(Map.of("from", "2026-03-14", "to", "2026-03-17", "totalCount", 2))
			.statusCode(409)
			.body("type", equalTo("urn:roomkeeper:problem:total-below-sold"))
			.body("nights", contains("2026-03-15", "2026-03-16"));

		// All or nothing: no night in the range changed
		nights("2026-03-14", "2026-03-17")
			.body("totalCount", contains(5, 5, 5, 5));
	}

	@Test
	void allowsLoweringATotalDownToWhatIsAlreadyBookedAndHeld() {
		sell("2026-03-15", 2, 1);

		update(Map.of("from", "2026-03-15", "to", "2026-03-15", "totalCount", 3))
			.statusCode(200)
			.body("[0].totalCount", equalTo(3))
			.body("[0].available", equalTo(0));
	}

	@Test
	void changesSeveralThingsAtOnce() {
		update(Map.of("from", "2026-03-20", "to", "2026-03-20", "totalCount", 2, "surgeFactor", 2, "closed", true))
			.statusCode(200)
			.body("[0].totalCount", equalTo(2))
			.body("[0].surgeFactor", equalTo(new BigDecimal("2.00")))
			.body("[0].closed", equalTo(true));
	}

	@Test
	void rejectsAnUpdateThatChangesNothing() {
		update(Map.of("from", "2026-03-10", "to", "2026-03-12"))
			.statusCode(400)
			.body("type", equalTo("urn:roomkeeper:problem:validation"));
	}

	@Test
	void rejectsARangeThatEndsBeforeItStarts() {
		update(Map.of("from", "2026-03-12", "to", "2026-03-11", "closed", true))
			.statusCode(400)
			.body("type", equalTo("urn:roomkeeper:problem:validation"))
			.body("errors.field", contains("to"));
		nights("2026-03-12", "2026-03-11")
			.statusCode(400)
			.body("errors.field", contains("to"));
	}

	@Test
	void rejectsRangesLongerThanAYear() {
		update(Map.of("from", "2026-03-10", "to", "2027-03-11", "closed", true))
			.statusCode(400)
			.body("errors.field", contains("to"));
	}

	@Test
	void rejectsChangesToPastNights() {
		update(Map.of("from", "2026-03-09", "to", "2026-03-12", "closed", true))
			.statusCode(400)
			.body("errors.field", contains("from"));
	}

	@Test
	void rejectsAnOutOfRangeSurgeOrTotal() {
		update(Map.of("from", "2026-03-10", "to", "2026-03-10", "surgeFactor", 0, "totalCount", -1))
			.statusCode(400)
			.body("errors.field", containsInAnyOrder("surgeFactor", "totalCount"));
		update(Map.of("from", "2026-03-10", "to", "2026-03-10", "surgeFactor", 1.234))
			.statusCode(400)
			.body("errors.field", contains("surgeFactor"));
	}

	@Test
	void anInactiveHotelHasNoInventoryToChange() {
		long inactiveHotel = createHotel(manager, aHotel().named("Not yet open").build());
		long inactiveRoom = createRoom(manager, inactiveHotel, aRoom().build());

		api(manager).contentType(ContentType.JSON).body(Map.of("from", "2026-03-10", "to", "2026-03-12", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", inactiveRoom)
			.then()
				.statusCode(409)
				.body("type", equalTo("urn:roomkeeper:problem:conflict"));
	}

	@Test
	void onlyTheHotelsOwnerCanSeeOrChangeItsInventory() {
		String otherManager = signUpAndLogIn(aUser().asHotelManager().build());

		api(otherManager)
			.when().get("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then()
				.statusCode(403)
				.body("type", equalTo("urn:roomkeeper:problem:forbidden"));
		api(otherManager).contentType(ContentType.JSON)
			.body(Map.of("from", "2026-03-10", "to", "2026-03-12", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then()
				.statusCode(403);

		nights("2026-03-10", "2026-03-12")
			.body("closed", everyItem(equalTo(false)));
	}

	@Test
	void unknownRoomIsNotFound() {
		api(manager)
			.when().get("/api/v1/admin/inventory/rooms/{roomId}", Long.MAX_VALUE)
			.then()
				.statusCode(404)
				.body("type", equalTo("urn:roomkeeper:problem:not-found"));
	}

	/** Stands in for bookings (week 5): marks rooms on a night as booked and held. */
	private void sell(String night, int booked, int reserved) {
		jdbc.update("update inventory set booked_count = ?, reserved_count = ? where room_id = ? and date = ?", booked,
				reserved, roomId, LocalDate.parse(night));
	}

	private ValidatableResponse nights(String from, String to) {
		return api(manager).queryParam("from", from).queryParam("to", to)
			.when().get("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then();
	}

	private ValidatableResponse update(Map<String, Object> body) {
		return api(manager).contentType(ContentType.JSON).body(body)
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then();
	}

}
