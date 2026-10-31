package com.roomkeeper.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.roomkeeper.auth.AccessTokenService;
import com.roomkeeper.outbox.Email;
import com.roomkeeper.outbox.FakeEmailSender;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.RaceTest;
import com.roomkeeper.testsupport.TestUser;
import com.roomkeeper.user.Role;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Guests wait for a sold-out stay; rooms a hold or a cancellation gives back go to the oldest
 * waiter whose stay now fits, as a normal hold made in the same transaction, so nobody can
 * take them first. The clock is moved and the expiry job run directly.
 */
@Import(MutableClockConfig.class)
class WaitlistIT extends AbstractIntegrationTest {

	private static final Instant HELD_AT = Instant.parse("2026-03-10T09:00:00Z");

	private static final Map<String, Object> STAY = stay("2026-03-10", "2026-03-12");

	@Autowired
	MutableClock clock;

	@Autowired
	HoldExpiryJob expiry;

	@Autowired
	AccessTokenService accessTokens;

	@Autowired
	FakeEmailSender emails;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	private long roomId;

	@BeforeEach
	void openAHotelWithOneRoom() {
		clock.setInstant(HELD_AT);
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		roomId = firstRoomOf(manager, activeHotel(manager, aHotel().named("Waitlist Inn").build(),
				aRoom().ofType("Deluxe").withTotalCount(1).build()));
	}

	@Test
	void aGuestJoinsTheWaitlistForASoldOutStay() {
		hold(signUpAndLogIn(aUser().build()), STAY).statusCode(201);
		String waiter = signUpAndLogIn(aUser().build());

		join(waiter, STAY).statusCode(201)
			.header("Location", notNullValue())
			.body("roomId", equalTo((int) roomId))
			.body("checkIn", equalTo("2026-03-10"))
			.body("checkOut", equalTo("2026-03-12"))
			.body("rooms", equalTo(1))
			.body("status", equalTo("WAITING"))
			.body("bookingId", nullValue());
	}

	@Test
	void aStayWithRoomsFreeIsBookedNotWaitedFor() {
		join(signUpAndLogIn(aUser().build()), STAY).statusCode(409)
			.body("type", equalTo("urn:roomkeeper:problem:stay-available"));
	}

	@Test
	void aGuestWaitsOnceForAStay() {
		hold(signUpAndLogIn(aUser().build()), STAY).statusCode(201);
		String waiter = signUpAndLogIn(aUser().build());
		join(waiter, STAY).statusCode(201);

		join(waiter, STAY).statusCode(409).body("type", equalTo("urn:roomkeeper:problem:already-waiting"));
	}

	@Test
	void aStayThatCouldntBeBookedCantBeWaitedFor() {
		hold(signUpAndLogIn(aUser().build()), STAY).statusCode(201);
		String waiter = signUpAndLogIn(aUser().build());

		join(waiter, stay("2026-03-09", "2026-03-11")).statusCode(400).body("errors.field", contains("checkIn"));
		join(waiter, stay("2026-03-10", "2026-04-20")).statusCode(400).body("errors.field", contains("checkOut"));
		join(waiter, Map.of("roomId", 999_999_999, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", 1))
			.statusCode(404);
	}

	@Test
	void guestsSeeOnlyTheirOwnEntriesNewestFirstAndCanLeave() {
		hold(signUpAndLogIn(aUser().build()), STAY).statusCode(201);
		hold(signUpAndLogIn(aUser().build()), stay("2026-03-14", "2026-03-15")).statusCode(201);
		String waiter = signUpAndLogIn(aUser().build());
		long first = id(join(waiter, STAY));
		long second = id(join(waiter, stay("2026-03-14", "2026-03-15")));

		api(waiter).when().get("/api/v1/waitlist").then().statusCode(200).body("id", contains((int) second, (int) first));
		api(signUpAndLogIn(aUser().build())).when().get("/api/v1/waitlist").then().statusCode(200).body("size()", equalTo(0));
		String someoneElse = signUpAndLogIn(aUser().build());
		api(someoneElse).when().get("/api/v1/waitlist/{id}", first).then().statusCode(404);
		api(someoneElse).when().delete("/api/v1/waitlist/{id}", first).then().statusCode(404);

		api(waiter).when().delete("/api/v1/waitlist/{id}", first).then().statusCode(204);
		api(waiter).when().get("/api/v1/waitlist").then().body("find { it.id == " + first + " }.status", equalTo("LEFT"));
	}

	@Test
	void anExpiredHoldGoesToTheOldestWaiterAndNeverBackOnSale() {
		long expiring = id(hold(signUpAndLogIn(aUser().build()), STAY));
		TestUser firstWaiter = aUser().build();
		String first = signUpAndLogIn(firstWaiter);
		String second = signUpAndLogIn(aUser().build());
		long firstEntry = id(join(first, STAY));
		clock.advance(Duration.ofSeconds(1));
		long secondEntry = id(join(second, STAY));

		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)));
		expiry.run();

		assertThat(storedStatus(expiring)).isEqualTo("EXPIRED");
		long offered = entry(first, firstEntry).body("status", equalTo("OFFERED"))
			.extract().jsonPath().getLong("bookingId");
		api(first).when().get("/api/v1/bookings/{id}", offered).then().statusCode(200)
			.body("status", equalTo("RESERVED"))
			.body("checkIn", equalTo("2026-03-10"))
			.body("checkOut", equalTo("2026-03-12"));
		entry(second, secondEntry).body("status", equalTo("WAITING"));
		assertThat(reservedOn("2026-03-10")).as("held for the waiter, not back on sale").isOne();
		hold(signUpAndLogIn(aUser().build()), STAY).statusCode(409).body("type", equalTo("urn:roomkeeper:problem:sold-out"));

		deliverOutbox();
		assertThat(emails.sentTo(firstWaiter.email())).extracting(Email::subject)
			.contains("Rooms are held for you at Waitlist Inn");
	}

	@Test
	void anOfferNobodyPaysForGoesToTheNextWaiter() {
		hold(signUpAndLogIn(aUser().build()), STAY);
		String first = signUpAndLogIn(aUser().build());
		TestUser secondWaiter = aUser().build();
		String second = signUpAndLogIn(secondWaiter);
		id(join(first, STAY));
		clock.advance(Duration.ofSeconds(1));
		long secondEntry = id(join(second, STAY));
		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)));
		expiry.run();

		// The first waiter doesn't pay for the hold they were given
		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(20)));
		expiry.run();
		// A fresh token: the first one was issued 20 minutes ago
		second = logIn(secondWaiter);

		entry(second, secondEntry).body("status", equalTo("OFFERED")).body("bookingId", notNullValue());
		assertThat(reservedOn("2026-03-10")).isOne();
	}

	@Test
	void theOldestWaiterWhoseStayFitsGetsTheRoomsTheOthersKeepWaiting() {
		hold(signUpAndLogIn(aUser().build()), STAY);
		// A night later in the week is taken for longer than the released hold
		hold(signUpAndLogIn(aUser().build()), stay("2026-03-12", "2026-03-13")).statusCode(201);
		String longer = signUpAndLogIn(aUser().build());
		String fits = signUpAndLogIn(aUser().build());
		long longerEntry = id(join(longer, stay("2026-03-10", "2026-03-13")));
		clock.advance(Duration.ofSeconds(1));
		long fitsEntry = id(join(fits, STAY));

		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)).plusSeconds(1));
		// The later night's hold runs out with the first, so release only the one: move it on
		jdbc.update("update bookings set hold_expires_at = hold_expires_at + interval '1 hour'"
				+ " where room_id = ? and check_in = '2026-03-12'", roomId);
		expiry.run();

		entry(longer, longerEntry).body("status", equalTo("WAITING"));
		entry(fits, fitsEntry).body("status", equalTo("OFFERED"));
	}

	@Test
	void aCancellationOffersItsRoomsToTheWaiter() {
		String guest = signUpAndLogIn(aUser().build());
		long paid = id(hold(guest, STAY));
		putAGuestOn(guest, paid);
		pay(guest, paid, "pi_waitlist_" + paid);
		String waiter = signUpAndLogIn(aUser().build());
		long entry = id(join(waiter, STAY));

		api(guest).when().post("/api/v1/bookings/{id}/cancel", paid).then().statusCode(200);

		entry(waiter, entry).body("status", equalTo("OFFERED"));
		assertThat(reservedOn("2026-03-10")).isOne();
		assertThat(jdbc.queryForObject("select booked_count from inventory where room_id = ? and date = '2026-03-10'",
				Integer.class, roomId)).isZero();
	}

	@Test
	void aWaiterWhoseStayHasStartedIsDroppedAndTheRoomsGoBackOnSale() {
		clock.setInstant(Instant.parse("2026-03-10T23:55:00Z"));
		hold(signUpAndLogIn(aUser().build()), STAY);
		String waiter = signUpAndLogIn(aUser().build());
		long entry = id(join(waiter, STAY));

		// The hold runs out after midnight: the stay began yesterday
		clock.setInstant(Instant.parse("2026-03-11T00:05:00Z"));
		expiry.run();

		entry(waiter, entry).body("status", equalTo("EXPIRED")).body("bookingId", nullValue());
		assertThat(reservedOn("2026-03-10")).isZero();
	}

	@Test
	void aWaiterWhoseOtherNightIsLockedIsSkippedWithoutWaiting() throws Exception {
		hold(signUpAndLogIn(aUser().build()), STAY);
		String waiter = signUpAndLogIn(aUser().build());
		long entry = id(join(waiter, stay("2026-03-10", "2026-03-13")));
		// Another transaction holds the third night's row, as a hold in progress would
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		Thread holder = Thread.ofPlatform().start(() -> transaction.executeWithoutResult(status -> {
			jdbc.queryForList("select id from inventory where room_id = ? and date = '2026-03-12' for update", roomId);
			locked.countDown();
			try {
				done.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}));
		try {
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
			clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)));

			assertTimeoutPreemptively(Duration.ofSeconds(3), expiry::run, "the release mustn't wait for the lock");
		}
		finally {
			done.countDown();
			holder.join();
		}

		entry(waiter, entry).body("status", equalTo("WAITING"));
		assertThat(reservedOn("2026-03-10")).as("back on sale: nobody could take it").isZero();
	}

	@RaceTest
	void releasedRoomsGoToTheWaiterEvenWhileOtherGuestsAskForThem() throws Exception {
		hold(signUpAndLogIn(aUser().build()), STAY);
		String waiter = signUpAndLogIn(aUser().build());
		long entry = id(join(waiter, STAY));
		List<String> others = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			// Issued directly: signing up 20 guests would hit the signup rate limit
			others.add(accessTokens.issue(insertGuestUser(), Role.GUEST).value());
		}
		clock.setInstant(HELD_AT.plus(Duration.ofMinutes(10)));
		List<Callable<Integer>> racers = new ArrayList<>();
		racers.add(() -> {
			expiry.run();
			return 0;
		});
		for (String other : others) {
			racers.add(() -> hold(other, STAY).extract().statusCode());
		}

		List<Integer> statuses = runTogether(racers);

		assertThat(statuses.subList(1, statuses.size())).as("every other guest").containsOnly(409);
		entry(waiter, entry).body("status", equalTo("OFFERED"));
		assertThat(jdbc.queryForObject("select count(*) from bookings where room_id = ? and status = 'RESERVED'",
				Integer.class, roomId)).isOne();
	}

	private ValidatableResponse hold(String guest, Map<String, Object> stay) {
		return idempotentApi(guest).contentType(ContentType.JSON).body(withRoom(stay))
			.when().post("/api/v1/bookings/init").then();
	}

	private ValidatableResponse join(String guest, Map<String, Object> stay) {
		return idempotentApi(guest).contentType(ContentType.JSON).body(withRoom(stay))
			.when().post("/api/v1/waitlist").then();
	}

	private ValidatableResponse entry(String guest, long entryId) {
		return api(guest).when().get("/api/v1/waitlist/{id}", entryId).then().statusCode(200);
	}

	private Map<String, Object> withRoom(Map<String, Object> stay) {
		return stay.containsKey("roomId") ? stay
				: Map.of("roomId", roomId, "checkIn", stay.get("checkIn"), "checkOut", stay.get("checkOut"), "rooms", 1);
	}

	private static Map<String, Object> stay(String checkIn, String checkOut) {
		return Map.of("checkIn", checkIn, "checkOut", checkOut);
	}

	private static long id(ValidatableResponse response) {
		return response.statusCode(201).extract().jsonPath().getLong("id");
	}

	/** The status recorded in the database, not the one the API works out from the clock. */
	private String storedStatus(long bookingId) {
		return jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId);
	}

	private int reservedOn(String night) {
		return jdbc.queryForObject("select reserved_count from inventory where room_id = ? and date = ?::date",
				Integer.class, roomId, night);
	}

}
