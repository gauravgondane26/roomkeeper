package com.roomkeeper.booking;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.roomkeeper.auth.AccessTokenService;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.user.Role;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature test: 50 guests ask for the last room at the same moment. Exactly one gets
 * it; the other 49 get a clean sold-out problem, and the room is held once.
 * <p>
 * Not @Transactional: each request runs in its own transaction on its own connection,
 * as in production, so they really race.
 */
class LastRoomConcurrencyIT extends AbstractIntegrationTest {

	private static final int GUESTS = 50;

	@Autowired
	AccessTokenService accessTokens;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@Test
	void exactlyOneOfFiftyConcurrentGuestsGetsTheLastRoom() throws Exception {
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		long hotelId = activeHotel(manager, aHotel().build(), aRoom().withTotalCount(1).build());
		long roomId = firstRoomOf(manager, hotelId);
		LocalDate checkIn = LocalDate.now(clock).plusDays(10);
		Map<String, Object> stay = Map.of("roomId", roomId, "checkIn", checkIn.toString(), "checkOut",
				checkIn.plusDays(2).toString(), "rooms", 1);
		// Tokens issued directly: signing up 50 guests would hit the signup rate limit
		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < GUESTS; i++) {
			tokens.add(accessTokens.issue(insertGuest(), Role.GUEST).value());
		}

		CountDownLatch start = new CountDownLatch(1);
		List<Response> responses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(GUESTS)) {
			List<Future<Response>> futures = new ArrayList<>();
			for (String token : tokens) {
				futures.add(pool.submit(() -> {
					start.await();
					return idempotentApi(token).contentType(ContentType.JSON).body(stay).when().post("/api/v1/bookings/init");
				}));
			}
			start.countDown();
			for (Future<Response> future : futures) {
				responses.add(future.get());
			}
		}

		List<Integer> statuses = responses.stream().map(Response::statusCode).toList();
		assertThat(statuses).as("one hold, 49 sold out").filteredOn(status -> status == 201).hasSize(1);
		assertThat(responses).filteredOn(response -> response.statusCode() != 201)
			.hasSize(GUESTS - 1)
			.allSatisfy(response -> {
				assertThat(response.statusCode()).isEqualTo(409);
				assertThat(response.jsonPath().getString("type")).isEqualTo("urn:roomkeeper:problem:sold-out");
			});
		assertThat(jdbc.queryForObject("select count(*) from bookings where room_id = ?", Integer.class, roomId))
			.isEqualTo(1);
		assertThat(jdbc.queryForList("select reserved_count from inventory where room_id = ? and date in (?, ?)",
				Integer.class, roomId, checkIn, checkIn.plusDays(1)))
			.containsExactly(1, 1);
	}

	private long insertGuest() {
		return jdbc.queryForObject("""
				insert into users (email, password_hash, name, role, created_at)
				values (?, 'not-a-real-hash', 'Racing Guest', 'GUEST', now()) returning id""",
				Long.class, "racer-" + UUID.randomUUID() + "@example.com");
	}

}
