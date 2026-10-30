package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.roomkeeper.inventory.InventoryRollJob;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Dates at the edges: hotels at the two ends of the world's time zones, the days clocks
 * change, a leap day inside the year on sale, and holidays that straddle New Year. Nights
 * are calendar dates in the hotel's own zone, so none of these may add, drop or shift one.
 */
@Import(MutableClockConfig.class)
class TimeZoneEdgesIT extends AbstractIntegrationTest {

	/** UTC+14 all year. */
	private static final String KIRITIMATI = "Pacific/Kiritimati";

	/** UTC-11 all year. */
	private static final String PAGO_PAGO = "Pacific/Pago_Pago";

	@Autowired
	MutableClock clock;

	@Autowired
	InventoryRollJob rollJob;

	@Autowired
	JdbcTemplate jdbc;

	private final String city = "Edges-" + UUID.randomUUID();

	@Test
	void hotelsAtUtcPlus14AndMinus11AreTwoCalendarDaysApart() {
		// 00:30 on the 11th in Kiritimati, 23:30 on the 9th in Pago Pago
		clock.setInstant(Instant.parse("2026-03-10T10:30:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		String guest = signUpAndLogIn(aUser().build());
		long ahead = activeHotel(manager, aHotel().inCity(city).inTimeZone(KIRITIMATI).build(), aRoom().build());
		long behind = activeHotel(manager, aHotel().inCity(city).inTimeZone(PAGO_PAGO).build(), aRoom().build());
		long aheadRoom = firstRoomOf(manager, ahead);
		long behindRoom = firstRoomOf(manager, behind);

		assertThat(nightDates(aheadRoom).getFirst()).isEqualTo("2026-03-11");
		assertThat(nightDates(behindRoom).getFirst()).isEqualTo("2026-03-09");

		// The 10th has already ended in Kiritimati and not yet begun in Pago Pago
		search("2026-03-10", "2026-03-11").body("hotels.id", contains((int) behind));
		book(guest, aheadRoom, "2026-03-10", "2026-03-11").statusCode(400).body("errors.field", contains("checkIn"));
		book(guest, behindRoom, "2026-03-10", "2026-03-11").statusCode(201);
		// Still tonight in Pago Pago
		book(guest, behindRoom, "2026-03-09", "2026-03-10").statusCode(201);
	}

	@Test
	void theDaysClocksChangeOpenOneNightEachLikeAnyOther() {
		// 23:30 on Saturday 7 March in New York; clocks go forward at 02:00 on the 8th
		clock.setInstant(Instant.parse("2026-03-08T04:30:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		long room = firstRoomOf(manager,
				activeHotel(manager, aHotel().inCity(city).inTimeZone("America/New_York").build(), aRoom().build()));

		// The hourly roll either side of the hour that doesn't exist (02:00-03:00 on 8 March)...
		rollAt("2026-03-08T05:30:00Z"); // 00:30 EST, the 8th
		rollAt("2026-03-08T07:30:00Z"); // 03:30 EDT
		rollAt("2026-03-09T04:30:00Z"); // 00:30 EDT, the 9th
		// ...and through the hour that happens twice (01:00-02:00 on 1 November)
		rollAt("2026-11-01T04:30:00Z"); // 00:30 EDT, the 1st
		rollAt("2026-11-01T05:30:00Z"); // 01:30 EDT
		rollAt("2026-11-01T06:30:00Z"); // 01:30 EST, again
		rollAt("2026-11-02T05:30:00Z"); // 00:30 EST, the 2nd

		LocalDate first = LocalDate.parse("2026-03-07");
		LocalDate last = LocalDate.parse("2027-11-01"); // a year on from the 2nd
		assertThat(nightDates(room)).as("one night for every date, none twice or missing")
			.doesNotHaveDuplicates()
			.hasSize((int) ChronoUnit.DAYS.between(first, last) + 1)
			.startsWith(first.toString())
			.endsWith(last.toString())
			.contains("2026-03-08", "2026-11-01");
	}

	@Test
	void aLeapDayInTheYearOnSaleIsANightLikeAnyOther() {
		clock.setInstant(Instant.parse("2027-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		String guest = signUpAndLogIn(aUser().build());
		long room = firstRoomOf(manager,
				activeHotel(manager, aHotel().inCity(city).build(), aRoom().pricedAt("4000").build()));

		assertThat(nightDates(room)).hasSize(365).startsWith("2027-03-10").endsWith("2028-03-08").contains("2028-02-29");
		search("2028-02-28", "2028-03-01").body("hotels", hasSize(1));
		// Monday 28 February and Tuesday 29 February 2028: two weekday nights
		book(guest, room, "2028-02-28", "2028-03-01").statusCode(201)
			.body("amount", equalTo(new BigDecimal("8000.00")));
	}

	@Test
	void holidaysEitherSideOfNewYearArePricedInOneStay() {
		clock.setInstant(Instant.parse("2026-12-01T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		String guest = signUpAndLogIn(aUser().build());
		long room = firstRoomOf(manager,
				activeHotel(manager, aHotel().inCity(city).build(), aRoom().pricedAt("4000").build()));

		// Wed 30 Dec: 4000. Thu 31 Dec, a holiday: 5000. Fri 1 Jan, a holiday and a weekend night: 5750
		assertThat(jdbc.queryForList("""
				select min_price::text from hotel_min_price p join rooms r on r.hotel_id = p.hotel_id
				where r.id = ? and p.date between '2026-12-30' and '2027-01-01' order by p.date""", String.class, room))
			.containsExactly("4000.00", "5000.00", "5750.00");
		book(guest, room, "2026-12-30", "2027-01-02").statusCode(201)
			.body("amount", equalTo(new BigDecimal("14750.00")));
	}

	private ValidatableResponse search(String checkIn, String checkOut) {
		return api().queryParam("city", city).queryParam("checkIn", checkIn).queryParam("checkOut", checkOut)
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200);
	}

	private ValidatableResponse book(String guest, long roomId, String checkIn, String checkOut) {
		return idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", checkIn, "checkOut", checkOut, "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then();
	}

	private void rollAt(String instant) {
		clock.setInstant(Instant.parse(instant));
		rollJob.run();
	}

	private List<String> nightDates(long roomId) {
		return jdbc.queryForList("select date::text from inventory where room_id = ? order by date", String.class,
				roomId);
	}

}
