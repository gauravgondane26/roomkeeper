package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.FakePaymentGateway;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.TestUser;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.StripeWebhooks.checkoutCompleted;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * A hotel's report over a range of nights: rooms sold and open, and revenue spread over the
 * nights stayed, counting what the hotel kept of cancelled bookings.
 */
@Import(MutableClockConfig.class)
class HotelReportIT extends AbstractIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-03-10T09:00:00Z");

	@Autowired
	MutableClock clock;

	@Autowired
	FakePaymentGateway stripe;

	private TestUser managerUser;

	private String manager;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotelWithFourRooms() {
		clock.setInstant(NOW);
		managerUser = aUser().asHotelManager().build();
		manager = signUpAndLogIn(managerUser);
		hotelId = activeHotel(manager, aHotel().build(), aRoom().withTotalCount(4).build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void countsRoomsSoldAndOpenAndTheRevenueKeptNightByNight() {
		// Two rooms for 12 and 13 March, paid
		BigDecimal stayed = paidBooking(aUser().build(), "2026-03-12", "2026-03-14", 2).amount();
		// One room for 13 March, paid, then cancelled with less than 48 hours' notice: half is kept
		TestUser canceller = aUser().build();
		Paid cancelled = paidBooking(canceller, "2026-03-13", "2026-03-14", 1);
		// An unpaid hold sells nothing
		idempotentApi(signUpAndLogIn(aUser().build())).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-12", "checkOut", "2026-03-13", "rooms", 1))
			.when().post("/api/v1/bookings/init").then().statusCode(201);
		// 14 March is closed
		api(manager).contentType(ContentType.JSON)
			.body(Map.of("from", "2026-03-14", "to", "2026-03-14", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId).then().statusCode(200);
		clock.setInstant(Instant.parse("2026-03-12T15:00:00Z"));
		api(logIn(canceller)).when().post("/api/v1/bookings/{id}/cancel", cancelled.bookingId()).then().statusCode(200);
		manager = logIn(managerUser);

		JsonPath report = report("2026-03-12", "2026-03-14").statusCode(200).extract().jsonPath();

		BigDecimal kept = cancelled.amount().subtract(half(cancelled.amount()));
		BigDecimal perNight = stayed.divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
		assertThat(report.getList("nights.date")).containsExactly("2026-03-12", "2026-03-13", "2026-03-14");
		assertThat(report.getList("nights.roomsBooked", Integer.class)).containsExactly(2, 2, 0);
		assertThat(report.getList("nights.roomsAvailable", Integer.class)).containsExactly(4, 4, 0);
		assertThat(report.getList("nights.revenue", BigDecimal.class)).usingElementComparator(BigDecimal::compareTo)
			.containsExactly(perNight, perNight.add(kept), BigDecimal.ZERO);
		assertThat(report.getInt("roomNightsSold")).isEqualTo(4);
		assertThat(report.getInt("roomNightsAvailable")).isEqualTo(8);
		assertThat(report.getObject("occupancy", BigDecimal.class)).isEqualByComparingTo("0.5");
		assertThat(report.getObject("revenue", BigDecimal.class)).isEqualByComparingTo(stayed.add(kept));
		assertThat(report.getObject("averageDailyRate", BigDecimal.class))
			.isEqualByComparingTo(stayed.add(kept).divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP));
	}

	@Test
	void nightsWithNothingOnSaleReportZeros() {
		JsonPath quiet = report("2026-03-11", "2026-03-11").statusCode(200).extract().jsonPath();
		assertThat(quiet.getInt("nights[0].roomsBooked")).isZero();
		assertThat(quiet.getInt("nights[0].roomsAvailable")).isEqualTo(4);
		assertThat(quiet.getInt("roomNightsSold")).isZero();
		assertThat(quiet.getObject("occupancy", BigDecimal.class)).isEqualByComparingTo("0");
		assertThat(quiet.getObject("revenue", BigDecimal.class)).isEqualByComparingTo("0");
		assertThat(quiet.getString("averageDailyRate")).as("no rate with nothing sold").isNull();
		// Before the hotel opened its year of nights: none on sale
		report("2026-03-01", "2026-03-01").statusCode(200).body("nights[0].roomsAvailable", equalTo(0));
	}

	@Test
	void refusesABackwardsOrTooLongRange() {
		report("2026-03-14", "2026-03-12").statusCode(400).body("errors[0].field", equalTo("to"));
		report("2026-03-01", "2027-03-02").statusCode(400).body("errors[0].field", equalTo("to"));
		api(manager).queryParam("from", "2026-03-12").when().get("/api/v1/admin/hotels/{id}/reports", hotelId)
			.then().statusCode(400);
	}

	@Test
	void onlyItsManagerSeesIt() {
		api(signUpAndLogIn(aUser().asHotelManager().build())).queryParam("from", "2026-03-12")
			.queryParam("to", "2026-03-13").when().get("/api/v1/admin/hotels/{id}/reports", hotelId)
			.then().statusCode(403);
		api(manager).queryParam("from", "2026-03-12").queryParam("to", "2026-03-13")
			.when().get("/api/v1/admin/hotels/{id}/reports", Long.MAX_VALUE).then().statusCode(404);
	}

	private record Paid(long bookingId, BigDecimal amount) {
	}

	/** Holds, puts a guest on, pays, and has Stripe report the payment; returns the booking. */
	private Paid paidBooking(TestUser user, String checkIn, String checkOut, int rooms) {
		String guest = signUpAndLogIn(user);
		JsonPath booking = idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", checkIn, "checkOut", checkOut, "rooms", rooms))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath();
		long bookingId = booking.getLong("id");
		putAGuestOn(guest, bookingId);
		idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", bookingId).then().statusCode(201);
		String sessionId = stripe.checkoutsFor(bookingId).getFirst().session().id();
		deliverWebhook(checkoutCompleted("evt_report_" + bookingId, sessionId, "pi_report_" + bookingId))
			.statusCode(200);
		return new Paid(bookingId, booking.getObject("amount", BigDecimal.class));
	}

	private static BigDecimal half(BigDecimal amount) {
		return amount.multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.HALF_UP);
	}

	private ValidatableResponse report(String from, String to) {
		return api(manager).queryParam("from", from).queryParam("to", to)
			.when().get("/api/v1/admin/hotels/{id}/reports", hotelId).then();
	}

}
