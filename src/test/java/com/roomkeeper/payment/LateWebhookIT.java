package com.roomkeeper.payment;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.StripeWebhooks.checkoutCompleted;
import static com.roomkeeper.testsupport.StripeWebhooks.signature;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
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

	private long roomId;

	private String guest;

	private long bookingId;

	private String sessionId;

	private String paymentIntentId;

	@BeforeEach
	void startPayingForTheOnlyRoomThenLetTheHoldRunOut() {
		clock.setInstant(HELD_AT);
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(1).build()));
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

	private ValidatableResponse deliver(String payload) {
		return api().contentType(ContentType.JSON)
			.header("Stripe-Signature", signature(payload, clock.instant()))
			.body(payload)
			.when().post("/webhook/payment")
			.then();
	}

}
