package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;

/**
 * POST /bookings/init holds rooms for a stay: it checks every night has enough rooms,
 * counts them as held, and freezes the price for 10 minutes.
 */
@Import(MutableClockConfig.class)
class BookingInitIT extends AbstractIntegrationTest {

	private static final String TUESDAY = "2026-03-10";

	private static final String WEDNESDAY = "2026-03-11";

	private static final String THURSDAY = "2026-03-12";

	private static final String SATURDAY = "2026-03-14";

	private static final String SOLD_OUT = "urn:roomkeeper:problem:sold-out";

	private static final String VALIDATION = "urn:roomkeeper:problem:validation";

	@Autowired
	MutableClock clock;

	private String manager;

	private String guest;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotel() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		hotelId = activeHotel(manager, aHotel().named("Seaside Inn").build(),
				aRoom().ofType("Deluxe").pricedAt("4000").sleeping(2).withTotalCount(5).build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void holdsTheRoomsForTenMinutesAtTheQuotedPrice() {
		ExtractableResponse<Response> response = init(stay(TUESDAY, THURSDAY, 2))
			.statusCode(201)
			.body("status", equalTo("RESERVED"))
			.body("hotelId", equalTo((int) hotelId))
			.body("hotelName", equalTo("Seaside Inn"))
			.body("roomId", equalTo((int) roomId))
			.body("roomType", equalTo("Deluxe"))
			.body("checkIn", equalTo(TUESDAY))
			.body("checkOut", equalTo(THURSDAY))
			.body("rooms", equalTo(2))
			.body("amount", equalTo(new BigDecimal("16000.00")))
			.body("holdExpiresAt", equalTo("2026-03-10T09:10:00Z"))
			.extract();

		assertThat(response.header("Location")).endsWith("/api/v1/bookings/" + response.jsonPath().getLong("id"));
		nightsOf(manager, roomId, TUESDAY, WEDNESDAY)
			.body("reservedCount", everyItem(equalTo(2)))
			.body("bookedCount", everyItem(equalTo(0)));
		nightsOf(manager, roomId, THURSDAY, THURSDAY).body("[0].reservedCount", equalTo(0));
	}

	@Test
	void pricesEachNightWithTheRules() {
		// Thursday at 4000.00, then Friday, a weekend night, at 4600.00
		init(stay(THURSDAY, SATURDAY, 1)).statusCode(201).body("amount", equalTo(new BigDecimal("8600.00")));
	}

	@Test
	void holdsTheLastRooms() {
		sellRooms(roomId, WEDNESDAY, 2, 1);

		init(stay(TUESDAY, THURSDAY, 2)).statusCode(201);

		nightsOf(manager, roomId, WEDNESDAY, WEDNESDAY).body("[0].available", equalTo(0));
	}

	@Test
	void refusesAStayWithFewerRoomsLeftOnAnyNight() {
		sellRooms(roomId, WEDNESDAY, 3, 1);

		init(stay(TUESDAY, THURSDAY, 2))
			.statusCode(409)
			.body("type", equalTo(SOLD_OUT));

		// Nothing held, not even on the night that had rooms
		nightsOf(manager, roomId, TUESDAY, WEDNESDAY)
			.body("[0].reservedCount", equalTo(0))
			.body("[1].reservedCount", equalTo(1));
	}

	@Test
	void refusesAStayOverAClosedNight() {
		api(manager).contentType(ContentType.JSON)
			.body(Map.of("from", WEDNESDAY, "to", WEDNESDAY, "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then().statusCode(200);

		init(stay(TUESDAY, THURSDAY, 1)).statusCode(409).body("type", equalTo(SOLD_OUT));
	}

	@Test
	void refusesAStayPastTheLastNightOnSale() {
		// Activation opened 365 nights from Tuesday: the last is 2027-03-09
		init(stay("2027-03-09", "2027-03-11", 1)).statusCode(409).body("type", equalTo(SOLD_OUT));
	}

	@Test
	void aHotelNotOnSaleHasNothingToBook() {
		long hotel = createHotel(manager, aHotel().build());
		long room = createRoom(manager, hotel, aRoom().build());

		init(stay(room, TUESDAY, THURSDAY, 1)).statusCode(404).body("type", equalTo("urn:roomkeeper:problem:not-found"));
	}

	@Test
	void anUnknownRoomIsNotFound() {
		init(stay(Long.MAX_VALUE, TUESDAY, THURSDAY, 1)).statusCode(404);
	}

	@Test
	void theCheckInIsJudgedByTheHotelsToday() {
		long kolkata = activeHotel(manager, aHotel().inTimeZone("Asia/Kolkata").build(), aRoom().build());
		long room = firstRoomOf(manager, kolkata);
		// 20:00 UTC on Tuesday is already Wednesday in Kolkata
		clock.setInstant(Instant.parse("2026-03-10T20:00:00Z"));
		guest = signUpAndLogIn(aUser().build());

		init(stay(room, TUESDAY, THURSDAY, 1))
			.statusCode(400)
			.body("type", equalTo(VALIDATION))
			.body("errors[0].field", equalTo("checkIn"));
		init(stay(room, WEDNESDAY, THURSDAY, 1)).statusCode(201);
	}

	@Test
	void rejectsACheckOutThatIsNotAfterTheCheckIn() {
		init(stay(TUESDAY, TUESDAY, 1)).statusCode(400).body("errors[0].field", equalTo("checkOut"));
	}

	@Test
	void rejectsAStayLongerThanThirtyNights() {
		init(stay(TUESDAY, "2026-04-10", 1)).statusCode(400).body("errors[0].field", equalTo("checkOut"));
		init(stay(TUESDAY, "2026-04-09", 1)).statusCode(201);
	}

	@Test
	void rejectsNoRoomsAndMoreThanTen() {
		init(stay(TUESDAY, THURSDAY, 0)).statusCode(400).body("errors[0].field", equalTo("rooms"));
		init(stay(TUESDAY, THURSDAY, 11)).statusCode(400).body("errors[0].field", equalTo("rooms"));
	}

	@Test
	void requiresALogIn() {
		api().contentType(ContentType.JSON).body(stay(TUESDAY, THURSDAY, 1))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(401);
	}

	private ValidatableResponse init(Map<String, Object> stay) {
		return idempotentApi(guest).contentType(ContentType.JSON).body(stay)
			.when().post("/api/v1/bookings/init")
			.then();
	}

	private Map<String, Object> stay(String checkIn, String checkOut, int rooms) {
		return stay(roomId, checkIn, checkOut, rooms);
	}

	private static Map<String, Object> stay(long roomId, String checkIn, String checkOut, int rooms) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("roomId", roomId);
		body.put("checkIn", checkIn);
		body.put("checkOut", checkOut);
		body.put("rooms", rooms);
		return body;
	}

}
