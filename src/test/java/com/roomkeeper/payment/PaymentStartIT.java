package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.FakePaymentGateway;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.RaceTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * POST /bookings/{id}/payments opens a Stripe Checkout session for the booking's frozen
 * amount and moves the booking to Payment pending. A retry gets the same session, never a
 * second one, and Stripe is never called while the booking is locked.
 */
@Import(MutableClockConfig.class)
class PaymentStartIT extends AbstractIntegrationTest {

	private static final String BOOKING_STATE = "urn:roomkeeper:problem:booking-state";

	private static final String HOLD_EXPIRED = "urn:roomkeeper:problem:hold-expired";

	@Autowired
	MutableClock clock;

	@Autowired
	FakePaymentGateway stripe;

	@Autowired
	JdbcTemplate jdbc;

	private String guest;

	private long roomId;

	private long bookingId;

	private BigDecimal amount;

	@BeforeEach
	void holdARoomWithAGuestOnIt() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		roomId = firstRoomOf(manager,
				activeHotel(manager, aHotel().named("Seaside Inn").build(), aRoom().ofType("Deluxe").build()));
		JsonPath booking = holdTuesdayAndWednesday(guest, roomId).statusCode(201).extract().jsonPath();
		bookingId = booking.getLong("id");
		amount = booking.get("amount");
		putAGuestOn(guest, bookingId);
	}

	@Test
	void opensACheckoutSessionForTheFrozenAmount() {
		pay("pay-1").statusCode(201)
			.body("bookingId", equalTo((int) bookingId))
			.body("status", equalTo("PENDING"))
			.body("amount", equalTo(amount))
			.body("currency", equalTo("inr"))
			.body("checkoutUrl", startsWith("https://checkout.stripe.test/"));

		assertThat(stripe.checkoutsFor(bookingId)).singleElement().satisfies(checkout -> {
			assertThat(checkout.request().amount()).isEqualByComparingTo(amount);
			assertThat(checkout.request().description()).isEqualTo("Deluxe at Seaside Inn, 2 nights, 1 room");
			// 31 minutes past the hold: Stripe keeps a session open at least 30 minutes
			assertThat(checkout.request().expiresAt()).isEqualTo(Instant.parse("2026-03-10T09:41:00Z"));
		});
		status().body("status", equalTo("PAYMENT_PENDING"));
	}

	@Test
	void aRetriedPaymentRequestReturnsTheSameSession() {
		String checkoutUrl = pay("pay-1").statusCode(201).extract().path("checkoutUrl");

		// The same key: the first response comes back
		pay("pay-1").statusCode(201)
			.header("Idempotent-Replayed", "true")
			.body("checkoutUrl", equalTo(checkoutUrl));
		// A new key while the session is still open: that session again, not a second one
		pay("pay-2").statusCode(200).body("checkoutUrl", equalTo(checkoutUrl));

		assertThat(stripe.checkoutsFor(bookingId)).hasSize(1);
		assertThat(paymentsForTheBooking()).isEqualTo(1);
	}

	@RaceTest
	void racingPaymentRequestsOpenOneSession() throws Exception {
		List<Callable<Response>> requests = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			requests.add(() -> idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", bookingId));
		}

		List<Response> responses = runTogether(requests);

		assertThat(responses).extracting(Response::statusCode).containsOnly(200, 201).contains(201);
		assertThat(responses).extracting(response -> response.jsonPath().getString("checkoutUrl")).containsOnly(
				responses.getFirst().jsonPath().getString("checkoutUrl"));
		assertThat(stripe.checkoutsFor(bookingId)).hasSize(1);
		assertThat(paymentsForTheBooking()).isEqualTo(1);
	}

	@Test
	void theGuestsGoOnBeforePaying() {
		long withoutGuests = holdTuesdayAndWednesday(guest, roomId).statusCode(201).extract().jsonPath().getLong("id");

		idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", withoutGuests)
			.then()
				.statusCode(409)
				.body("type", equalTo(BOOKING_STATE));

		assertThat(stripe.checkoutsFor(withoutGuests)).isEmpty();
	}

	@Test
	void notOnceTheHoldRanOut() {
		clock.advance(Duration.ofMinutes(10));

		pay("pay-1").statusCode(409).body("type", equalTo(HOLD_EXPIRED));

		assertThat(stripe.checkoutsFor(bookingId)).isEmpty();
	}

	@Test
	void aHoldThatRunsOutWhileStripeIsCalledHasItsSessionClosed() {
		stripe.beforeNextCheckout(() -> clock.advance(Duration.ofMinutes(10)));

		pay("pay-1").statusCode(409).body("type", equalTo(HOLD_EXPIRED));

		assertThat(stripe.checkoutsFor(bookingId)).singleElement()
			.satisfies(checkout -> assertThat(stripe.wasExpired(checkout.session().id())).isTrue());
		assertThat(paymentsForTheBooking()).isZero();
	}

	@Test
	void stripeBeingDownChangesNothing() {
		stripe.failNextCheckout();

		pay("pay-1").statusCode(503).body("type", equalTo("urn:roomkeeper:problem:payments-unavailable"));

		status().body("status", equalTo("GUESTS_ADDED"));
		assertThat(paymentsForTheBooking()).isZero();
		// The failure didn't keep the key, so retrying the same request goes through
		pay("pay-1").statusCode(201);
	}

	@Test
	void onlyTheGuestPaysForTheirBooking() {
		idempotentApi(signUpAndLogIn(aUser().build())).when().post("/api/v1/bookings/{id}/payments", bookingId)
			.then().statusCode(404);

		assertThat(stripe.checkoutsFor(bookingId)).isEmpty();
	}

	@Test
	void payingNeedsAnIdempotencyKey() {
		api(guest).when().post("/api/v1/bookings/{id}/payments", bookingId)
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:idempotency-key-required"));
	}

	@Test
	void stripeSendsTheGuestBackToPagesAnyoneCanOpen() {
		api().accept("text/html").when().get("/payments/complete.html").then().statusCode(200);
		api().accept("text/html").when().get("/payments/cancelled.html").then().statusCode(200);
	}


	private ValidatableResponse pay(String idempotencyKey) {
		return api(guest).header("Idempotency-Key", idempotencyKey)
			.when().post("/api/v1/bookings/{id}/payments", bookingId)
			.then();
	}

	private ValidatableResponse status() {
		return api(guest).when().get("/api/v1/bookings/{id}/status", bookingId).then().statusCode(200);
	}

	private long paymentsForTheBooking() {
		return jdbc.queryForObject("select count(*) from payments where booking_id = ?", Long.class, bookingId);
	}

}
