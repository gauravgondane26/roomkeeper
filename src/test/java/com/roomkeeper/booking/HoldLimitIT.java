package com.roomkeeper.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.RaceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * A guest holds at most 3 stays at once, unpaid and not yet expired, and asks for holds at
 * most 20 times a minute: otherwise one account could keep a hotel's rooms held, 10 minutes
 * at a time, and the hotel would look sold out to everyone else.
 */
@Import(MutableClockConfig.class)
class HoldLimitIT extends AbstractIntegrationTest {

	private static final String TOO_MANY_HOLDS = "urn:roomkeeper:problem:too-many-holds";

	@Autowired
	MutableClock clock;

	private long roomId;

	@BeforeEach
	void openAHotelWithRoomsToSpare() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(50).build()));
	}

	@Test
	void aFourthHoldAtOnceIsRefused() {
		String guest = signUpAndLogIn(aUser().build());
		for (int hold = 1; hold <= 3; hold++) {
			holdTuesdayAndWednesday(guest, roomId).statusCode(201);
		}

		holdTuesdayAndWednesday(guest, roomId).statusCode(409).body("type", equalTo(TOO_MANY_HOLDS));
	}

	@Test
	void aHoldThatRanOutNoLongerCounts() {
		String guest = signUpAndLogIn(aUser().build());
		for (int hold = 1; hold <= 3; hold++) {
			holdTuesdayAndWednesday(guest, roomId).statusCode(201);
		}

		// Run out, but not yet released by the expiry job
		clock.advance(Duration.ofMinutes(10));

		holdTuesdayAndWednesday(guest, roomId).statusCode(201);
	}

	@Test
	void aPaidBookingNoLongerCounts() {
		String guest = signUpAndLogIn(aUser().build());
		long first = holdTuesdayAndWednesday(guest, roomId).statusCode(201).extract().jsonPath().getLong("id");
		holdTuesdayAndWednesday(guest, roomId).statusCode(201);
		holdTuesdayAndWednesday(guest, roomId).statusCode(201);

		pay(guest, first, "pi_hold_limit_" + first);

		holdTuesdayAndWednesday(guest, roomId).statusCode(201);
	}

	@Test
	void otherGuestsAreUnaffected() {
		String hoarder = signUpAndLogIn(aUser().build());
		for (int hold = 1; hold <= 3; hold++) {
			holdTuesdayAndWednesday(hoarder, roomId).statusCode(201);
		}

		holdTuesdayAndWednesday(signUpAndLogIn(aUser().build()), roomId).statusCode(201);
	}

	@RaceTest
	void holdsSentTogetherStillStopAtThree() throws Exception {
		String guest = signUpAndLogIn(aUser().build());
		List<Callable<Integer>> holds = new ArrayList<>();
		for (int hold = 1; hold <= 6; hold++) {
			holds.add(() -> holdTuesdayAndWednesday(guest, roomId).extract().statusCode());
		}

		List<Integer> statuses = runTogether(holds);

		assertThat(statuses).filteredOn(status -> status == 201).hasSize(3);
		assertThat(statuses).filteredOn(status -> status == 409).hasSize(3);
	}

	@Test
	void holdRequestsAreLimitedPerGuest() {
		String guest = signUpAndLogIn(aUser().build());
		// 3 holds, then refusals: every request counts towards the 20 a minute
		for (int request = 1; request <= 20; request++) {
			holdTuesdayAndWednesday(guest, roomId);
		}

		holdTuesdayAndWednesday(guest, roomId).statusCode(429)
			.body("type", equalTo("urn:roomkeeper:problem:rate-limited"));
		// Per guest, not per IP: another guest from the same address still gets through
		holdTuesdayAndWednesday(signUpAndLogIn(aUser().build()), roomId).statusCode(201);
	}

}
