package com.roomkeeper.hotel;

import java.time.Instant;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * A night is a date in the hotel's own time zone. At 20:00 UTC on 10 March it is already
 * 11 March in Kolkata (UTC+5:30) but still 10 March in Los Angeles (UTC-7).
 */
@Import(MutableClockConfig.class)
class HotelTimeZoneIT extends AbstractIntegrationTest {

	@Autowired
	MutableClock clock;

	private String manager;

	@BeforeEach
	void setTheClock() {
		clock.setInstant(Instant.parse("2026-03-10T20:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
	}

	@Test
	void inventoryStartsOnTheHotelsLocalDate() {
		long kolkataRoom = activeHotelWithARoom("Asia/Kolkata");
		long losAngelesRoom = activeHotelWithARoom("America/Los_Angeles");

		firstNightOf(kolkataRoom, "2026-03-11");
		firstNightOf(losAngelesRoom, "2026-03-10");
	}

	@Test
	void pastNightsAreJudgedByTheHotelsLocalDate() {
		long kolkataRoom = activeHotelWithARoom("Asia/Kolkata");

		// 10 March has already ended in Kolkata
		api(manager).contentType(ContentType.JSON)
			.body(Map.of("from", "2026-03-10", "to", "2026-03-12", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", kolkataRoom)
			.then()
				.statusCode(400)
				.body("errors.field", contains("from"));

		api(manager).contentType(ContentType.JSON)
			.body(Map.of("from", "2026-03-11", "to", "2026-03-12", "closed", true))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", kolkataRoom)
			.then()
				.statusCode(200);
	}

	private long activeHotelWithARoom(String timeZone) {
		long hotelId = createHotel(manager, aHotel().inTimeZone(timeZone).build());
		long roomId = createRoom(manager, hotelId, aRoom().build());
		activateHotel(manager, hotelId);
		return roomId;
	}

	private void firstNightOf(long roomId, String expectedDate) {
		api(manager)
			.when().get("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then()
				.statusCode(200)
				.body("[0].date", equalTo(expectedDate));
	}

}
