package com.roomkeeper.payment;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.roomkeeper.booking.HoldExpiryJob;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.FakePaymentGateway;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.StripeWebhooks.checkoutCompleted;
import static com.roomkeeper.testsupport.StripeWebhooks.signature;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Signature test: the guest pays after the hold ran out and its room went back on sale.
 * Stripe's checkout page stays open longer than the hold, so this happens. The payment
 * ends confirmed if the room is still free, or refunded in full if it isn't; never stuck.
 */
@Import(MutableClockConfig.class)
class LateWebhookIT extends AbstractIntegrationTest {

	private static final Instant HELD_AT = Instant.parse("2026-03-10T09:00:00Z");

	private static final LocalDate FIRST_NIGHT = LocalDate.parse("2026-03-10");

	@Autowired
	MutableClock clock;

	@Autowired
	FakePaymentGateway stripe;

	@Autowired
	HoldExpiryJob job;

	@Autowired
	JdbcTemplate jdbc;

	private String manager;

	private String city;

	private long hotelId;

	private long roomId;

	private String guest;

	private long bookingId;

	private String sessionId;

	private String paymentIntentId;

	@BeforeEach
	void startPayingForTheOnlyRoomThenLetTheHoldRunOut() {
		clock.setInstant(HELD_AT);
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		city = "Late-" + UUID.randomUUID();
		hotelId = activeHotel(manager, aHotel().inCity(city).build(), aRoom().withTotalCount(1).build());
		roomId = firstRoomOf(manager, hotelId);
		guest = signUpAndLogIn(aUser().build());
		bookingId = hold(guest);
		long asha = api(guest).contentType(ContentType.JSON).body(Map.of("name", "Asha", "age", 30, "gender", "FEMALE"))
			.when().post("/api/v1/users/guests")
			.then().statusCode(201).extract().jsonPath().getLong("id");
		api(guest).contentType(ContentType.JSON).body(Map.of("guestIds", List.of(asha)))
			.when().post("/api/v1/bookings/{id}/guests", bookingId)
			.then().statusCode(200);
		idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", bookingId).then().statusCode(201);
		sessionId = stripe.checkoutsFor(bookingId).getFirst().session().id();
		paymentIntentId = "pi_late_" + bookingId;

		clock.advance(Duration.ofMinutes(10));
		job.run();
		assertThat(bookingStatus()).as("the hold ran out and its room went back on sale").isEqualTo("EXPIRED");
	}

	@Test
	void aLatePaymentConfirmsTheBookingWhileTheRoomIsStillFree() {
		deliver(checkoutCompleted("evt_late_" + bookingId, sessionId, paymentIntentId)).statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("CONFIRMED"));
		assertThat(nights()).hasSize(2).allSatisfy(night -> assertThat(night)
			.containsEntry("booked_count", 1)
			.containsEntry("reserved_count", 0));
		assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
		assertThat(stripe.refundsOf(paymentIntentId)).isZero();
	}

	@Test
	void aLatePaymentIsRefundedWhenTheRoomWasTakenMeanwhile() {
		hold(signUpAndLogIn(aUser().build()));

		deliver(checkoutCompleted("evt_late_" + bookingId, sessionId, paymentIntentId)).statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("EXPIRED"));
		assertThat(paymentStatus()).isEqualTo("REFUNDED");
		assertThat(stripe.refundsOf(paymentIntentId)).isOne();
		// The other guest's hold is untouched
		assertThat(nights()).hasSize(2).allSatisfy(night -> assertThat(night)
			.containsEntry("booked_count", 0)
			.containsEntry("reserved_count", 1));
	}

	@Test
	void theExpiryJobClosesTheCheckoutSessionOfTheHoldItReleased() {
		// Closed after the release commits, off the job's thread, so a slow Stripe can't hold up releases
		await().until(() -> stripe.wasExpired(sessionId));
		// Stripe reports the session expired with an event of its own; until then it's still open
		assertThat(paymentStatus()).isEqualTo("PENDING");
	}

	@Test
	void aLatePaymentThatSellsOutTheRoomTakesTheHotelOutOfSearch() {
		await().untilAsserted(() -> searchFinds(1));

		deliver(checkoutCompleted("evt_late_" + bookingId, sessionId, paymentIntentId)).statusCode(200);

		await().untilAsserted(() -> searchFinds(0));
	}

	@Test
	void aLatePaymentIsRefundedOnceHoweverOftenItArrives() {
		hold(signUpAndLogIn(aUser().build()));
		String paid = checkoutCompleted("evt_late_" + bookingId, sessionId, paymentIntentId);

		deliver(paid).statusCode(200);
		deliver(paid).statusCode(200);
		deliver(checkoutCompleted("evt_another_" + bookingId, sessionId, paymentIntentId)).statusCode(200);

		assertThat(stripe.refundsOf(paymentIntentId)).isOne();
		assertThat(paymentStatus()).isEqualTo("REFUNDED");
	}

	@Test
	void racingDeliveriesOfALatePaymentRefundItOnce() throws Exception {
		hold(signUpAndLogIn(aUser().build()));
		String paid = checkoutCompleted("evt_late_racing_" + bookingId, sessionId, paymentIntentId);
		List<Callable<Integer>> deliveries = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			deliveries.add(() -> deliver(paid).extract().statusCode());
		}

		assertThat(runTogether(deliveries)).containsOnly(200);

		assertThat(stripe.refundsOf(paymentIntentId)).isOne();
		assertThat(paymentStatus()).isEqualTo("REFUNDED");
	}

	@Test
	void aRefundStripeCantMakeYetIsMadeWhenStripeRetriesTheEvent() {
		hold(signUpAndLogIn(aUser().build()));
		String paid = checkoutCompleted("evt_late_" + bookingId, sessionId, paymentIntentId);
		stripe.failNextRefund();

		deliver(paid)
			.statusCode(503)
			.body("type", equalTo("urn:roomkeeper:problem:payments-unavailable"));
		// Not recorded, so Stripe's retry isn't ignored as a duplicate
		assertThat(jdbc.queryForObject("select count(*) from processed_webhook_events where event_id = ?", Long.class,
				"evt_late_" + bookingId))
			.isZero();
		assertThat(paymentStatus()).isEqualTo("PENDING");

		deliver(paid).statusCode(200);

		assertThat(stripe.refundsOf(paymentIntentId)).isOne();
		assertThat(paymentStatus()).isEqualTo("REFUNDED");
	}

	@Test
	void aLatePaymentIsRefundedWhenTheHotelWentOffSale() {
		api(manager).when().patch("/api/v1/admin/hotels/{id}/deactivate", hotelId).then().statusCode(200);

		deliver(checkoutCompleted("evt_late_" + bookingId, sessionId, paymentIntentId)).statusCode(200);

		assertThat(bookingStatus()).isEqualTo("EXPIRED");
		assertThat(paymentStatus()).isEqualTo("REFUNDED");
		assertThat(nights()).hasSize(2).allSatisfy(night -> assertThat(night).containsEntry("booked_count", 0));
	}

	@Test
	void aLatePaymentIsRefundedOnceTheStayHasBegun() {
		// Stripe retries an event for up to three days, so a payment can be reported this late
		clock.setInstant(HELD_AT.plus(Duration.ofDays(2)));

		deliver(checkoutCompleted("evt_late_" + bookingId, sessionId, paymentIntentId)).statusCode(200);

		assertThat(bookingStatus()).isEqualTo("EXPIRED");
		assertThat(paymentStatus()).isEqualTo("REFUNDED");
		assertThat(nights()).hasSize(2).allSatisfy(night -> assertThat(night).containsEntry("booked_count", 0));
	}

	/** Holds the room for Tuesday and Wednesday nights. */
	private long hold(String token) {
		return idempotentApi(token).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
	}

	private String bookingStatus() {
		return jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId);
	}

	private String paymentStatus() {
		return jdbc.queryForObject("select status from payments where provider_session_id = ?", String.class,
				sessionId);
	}

	private List<Map<String, Object>> nights() {
		return jdbc.queryForList("select booked_count, reserved_count from inventory where room_id = ?"
				+ " and date in (?, ?)", roomId, FIRST_NIGHT, FIRST_NIGHT.plusDays(1));
	}

	private void searchFinds(int hotels) {
		api().queryParam("city", city).queryParam("checkIn", "2026-03-10").queryParam("checkOut", "2026-03-12")
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200).body("totalHotels", equalTo(hotels));
	}

	private ValidatableResponse deliver(String payload) {
		return api().contentType(ContentType.JSON)
			.header("Stripe-Signature", signature(payload, clock.instant()))
			.body(payload)
			.when().post("/webhook/payment")
			.then();
	}

}
