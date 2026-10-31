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
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optimistic way of holding rooms, kept to benchmark against the default pessimistic
 * one (see docs/locking-benchmark.md): nights are read without a lock and written back
 * only if their version hasn't moved, and a hold that loses that race starts over. It must
 * be exactly as correct: the last room still goes to one guest, overlapping holds all get
 * their rooms, and a hold that keeps losing gives up cleanly, holding nothing.
 */
// Enough attempts that 20 holds colliding on the same nights all get through; the default 5
// gives some up under that much contention, which docs/locking-benchmark.md measures
@TestPropertySource(properties = { "roomkeeper.booking.locking=optimistic", "roomkeeper.booking.optimistic-attempts=50" })
class OptimisticLockingIT extends AbstractIntegrationTest {

	@Autowired
	AccessTokenService accessTokens;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MeterRegistry meters;

	@Autowired
	Clock clock;

	@RaceTest
	void theLastRoomStillGoesToExactlyOneOfFiftyGuests() throws Exception {
		Stay stay = stayAtANewHotel(1);

		List<Response> responses = holdTogether(stay, 50);

		assertThat(responses).filteredOn(response -> response.statusCode() == 201).hasSize(1);
		assertThat(responses).filteredOn(response -> response.statusCode() != 201)
			.hasSize(49)
			.allSatisfy(response -> assertThat(response.jsonPath().getString("type"))
				.isEqualTo("urn:roomkeeper:problem:sold-out"));
		assertThat(reserved(stay)).containsExactly(1, 1);
	}

	@RaceTest
	void overlappingHoldsAllGetTheirRoomsByTryingAgain() throws Exception {
		Stay stay = stayAtANewHotel(100);
		double retriesBefore = retries();

		List<Response> responses = holdTogether(stay, 20);

		assertThat(responses).extracting(Response::statusCode).containsOnly(201);
		assertThat(reserved(stay)).containsExactly(20, 20);
		assertThat(retries()).as("holds that lost a race and tried again").isGreaterThan(retriesBefore);
	}

	/** With a single attempt, a hold that loses the race has nowhere to go. */
	@Nested
	@TestPropertySource(properties = "roomkeeper.booking.optimistic-attempts=1")
	class WithOneAttempt {

		@RaceTest
		void aHoldThatLosesTheRaceGivesUpWithA503AndHoldsNothing() throws Exception {
			Stay stay = stayAtANewHotel(100);

			List<Response> responses = holdTogether(stay, 20);

			List<Response> held = responses.stream().filter(response -> response.statusCode() == 201).toList();
			assertThat(responses).filteredOn(response -> response.statusCode() != 201)
				.isNotEmpty()
				.allSatisfy(response -> {
					assertThat(response.statusCode()).isEqualTo(503);
					assertThat(response.jsonPath().getString("type")).isEqualTo("urn:roomkeeper:problem:booking-busy");
				});
			assertThat(reserved(stay)).containsOnly(held.size());
		}

	}

	private record Stay(long roomId, LocalDate checkIn, LocalDate checkOut) {

		Map<String, Object> body() {
			return Map.of("roomId", roomId, "checkIn", checkIn.toString(), "checkOut", checkOut.toString(), "rooms", 1);
		}

	}

	private Stay stayAtANewHotel(int rooms) {
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		long roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(rooms).build()));
		LocalDate checkIn = LocalDate.now(clock).plusDays(10);
		return new Stay(roomId, checkIn, checkIn.plusDays(2));
	}

	private List<Response> holdTogether(Stay stay, int guests) throws Exception {
		// Tokens issued directly: signing up this many guests would hit the signup rate limit
		List<Callable<Response>> holds = new ArrayList<>();
		for (int i = 0; i < guests; i++) {
			String token = accessTokens.issue(insertGuestUser(), Role.GUEST).value();
			holds.add(() -> idempotentApi(token).contentType(ContentType.JSON).body(stay.body())
				.when().post("/api/v1/bookings/init"));
		}
		return runTogether(holds);
	}

	private List<Integer> reserved(Stay stay) {
		return jdbc.queryForList("select reserved_count from inventory where room_id = ? and date >= ? and date < ?"
				+ " order by date", Integer.class, stay.roomId(), stay.checkIn(), stay.checkOut());
	}

	private double retries() {
		var counter = meters.find("roomkeeper.bookings.optimistic.retries").counter();
		return counter == null ? 0 : counter.count();
	}

}
