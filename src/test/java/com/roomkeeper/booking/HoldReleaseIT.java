package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * What the hold expiry job releases, and what it leaves alone. Releasing rooms is a
 * change to inventory like any other: cheapest prices and cached searches follow it.
 */
@Import(MutableClockConfig.class)
class HoldReleaseIT extends AbstractIntegrationTest {

	private static final Instant HELD_AT = Instant.parse("2026-03-10T09:00:00Z");

	@Autowired
	MutableClock clock;

	@Autowired
	HoldExpiryJob job;

	@Autowired
	JdbcTemplate jdbc;

	private String guest;

	private String city;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotel() {
		clock.setInstant(HELD_AT);
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		city = "Expiry-" + UUID.randomUUID();
		hotelId = activeHotel(manager, aHotel().inCity(city).build(),
				aRoom().pricedAt("4000").sleeping(2).withTotalCount(3).build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void releasesEveryRoomAndNightOfTheHold() {
		long booking = hold(3);

		expireHolds();

		assertThat(status(booking)).isEqualTo("EXPIRED");
		assertThat(reservedCounts()).containsExactly(0, 0);
	}

	@Test
	void releasesAHoldWithGuestsOnIt() {
		long booking = hold(1);
		putAGuestOn(guest, booking);

		expireHolds();

		assertThat(status(booking)).isEqualTo("EXPIRED");
		assertThat(reservedCounts()).containsExactly(0, 0);
	}

	@Test
	void leavesConfirmedBookingsAlone() {
		long booking = hold(1);
		// Confirmed directly, standing in for a paid webhook: the rooms are booked, not held
		jdbc.update("update bookings set status = 'CONFIRMED' where id = ?", booking);
		jdbc.update("update inventory set reserved_count = 0, booked_count = 1 where room_id = ?"
				+ " and date between '2026-03-10' and '2026-03-11'", roomId);

		expireHolds();

		assertThat(status(booking)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForList("select booked_count from inventory where room_id = ?"
				+ " and date between '2026-03-10' and '2026-03-11'", Integer.class, roomId)).containsExactly(1, 1);
	}

	@Test
	void leavesHoldsThatHaventRunOut() {
		long early = hold(1);
		clock.advance(Duration.ofMinutes(5));
		long late = hold(1);

		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)));
		job.run();

		assertThat(status(early)).isEqualTo("EXPIRED");
		assertThat(status(late)).isEqualTo("RESERVED");
		assertThat(reservedCounts()).containsExactly(1, 1);
	}

	@Test
	void releasedRoomsArePricedAndSearchableAgain() {
		hold(3);
		await().untilAsserted(() -> assertThat(minPrices()).as("sold out").isEmpty());
		searchFinds(0);

		expireHolds();

		await().untilAsserted(() -> assertThat(minPrices()).hasSize(2));
		await().untilAsserted(() -> searchFinds(1));
	}

	/** Holds rooms for Tuesday and Wednesday nights. */
	private long hold(int rooms) {
		return idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", rooms))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
	}

	private void expireHolds() {
		clock.advance(Duration.ofMinutes(10));
		job.run();
	}

	private String status(long bookingId) {
		return jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId);
	}

	private List<Integer> reservedCounts() {
		return jdbc.queryForList("select reserved_count from inventory where room_id = ?"
				+ " and date between '2026-03-10' and '2026-03-11' order by date", Integer.class, roomId);
	}

	private List<BigDecimal> minPrices() {
		return jdbc.queryForList("select min_price from hotel_min_price where hotel_id = ?"
				+ " and date between '2026-03-10' and '2026-03-11'", BigDecimal.class, hotelId);
	}

	private void searchFinds(int hotels) {
		api().queryParam("city", city).queryParam("checkIn", "2026-03-10").queryParam("checkOut", "2026-03-12")
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200).body("totalHotels", equalTo(hotels));
	}

}
