package com.roomkeeper.booking;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.roomkeeper.auth.AccessTokenService;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.RaceTest;
import com.roomkeeper.user.Role;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guests booking while the manager changes the same nights: room type totals, a night's
 * total and surge. Every writer locks the nights in date order, so none of them deadlock,
 * every request gets a proper answer, and the counts add up afterwards.
 * <p>
 * Not @Transactional: each request races in its own transaction.
 */
class BookingRacesIT extends AbstractIntegrationTest {

	private static final int GUESTS = 20;

	private static final String SOLD_OUT = "urn:roomkeeper:problem:sold-out";

	private static final String TOTAL_BELOW_SOLD = "urn:roomkeeper:problem:total-below-sold";

	@Autowired
	AccessTokenService accessTokens;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@RaceTest
	void bookingsAndManagersChangingTheSameNightsAllGetAnswersAndTheCountsAddUp() throws Exception {
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		long hotelId = activeHotel(manager, aHotel().build(), aRoom().ofType("Deluxe").withTotalCount(10).build());
		long roomId = firstRoomOf(manager, hotelId);
		LocalDate checkIn = LocalDate.now(clock).plusDays(20);
		LocalDate secondNight = checkIn.plusDays(1);
		Map<String, Object> stay = Map.of("roomId", roomId, "checkIn", checkIn.toString(), "checkOut",
				checkIn.plusDays(2).toString(), "rooms", 1);

		List<Callable<Response>> requests = new ArrayList<>();
		for (int i = 0; i < GUESTS; i++) {
			String guest = accessTokens.issue(insertGuestUser(), Role.GUEST).value();
			requests.add(() -> idempotentApi(guest).contentType(ContentType.JSON).body(stay).post("/api/v1/bookings/init"));
		}
		for (int total : new int[] { 12, 9, 14, 8, 11 }) {
			// The room type's total, which every night follows unless set by hand
			requests.add(() -> api(manager).contentType(ContentType.JSON)
				.body(aRoom().ofType("Deluxe").withTotalCount(total).build())
				.put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, roomId));
			// One night's total and surge, set by hand
			requests.add(() -> api(manager).contentType(ContentType.JSON)
				.body(Map.of("from", secondNight.toString(), "to", secondNight.toString(), "totalCount", total,
						"surgeFactor", 1.5))
				.patch("/api/v1/admin/inventory/rooms/{roomId}", roomId));
		}

		List<Response> responses = runTogether(requests);

		List<Response> holds = responses.subList(0, GUESTS);
		List<Response> edits = responses.subList(GUESTS, responses.size());
		assertThat(holds).allSatisfy(response -> assertThat(response.statusCode() == 201
				|| isProblem(response, 409, SOLD_OUT)).as(response.asString()).isTrue());
		assertThat(edits).allSatisfy(response -> assertThat(response.statusCode() == 200
				|| isProblem(response, 409, TOTAL_BELOW_SOLD)).as(response.asString()).isTrue());

		long held = holds.stream().filter(response -> response.statusCode() == 201).count();
		assertThat(held).isPositive();
		assertThat(jdbc.queryForObject("select count(*) from bookings where room_id = ?", Long.class, roomId))
			.isEqualTo(held);
		List<Map<String, Object>> nights = jdbc.queryForList("""
				select reserved_count, booked_count, total_count from inventory
				where room_id = ? and date in (?, ?) order by date""", roomId, checkIn, secondNight);
		assertThat(nights).hasSize(2).allSatisfy(night -> {
			assertThat(((Number) night.get("reserved_count")).longValue()).isEqualTo(held);
			assertThat((Integer) night.get("reserved_count") + (Integer) night.get("booked_count"))
				.isLessThanOrEqualTo((Integer) night.get("total_count"));
		});
	}

	private static boolean isProblem(Response response, int status, String type) {
		return response.statusCode() == status && type.equals(response.jsonPath().getString("type"));
	}

}
