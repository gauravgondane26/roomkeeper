package com.roomkeeper.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
 * Stripe reports payments to POST /webhook/payment, at least once: the same event can
 * arrive again. Only a verified event changes anything, and each event changes it once.
 */
@Import(MutableClockConfig.class)
class PaymentWebhookIT extends AbstractIntegrationTest {

	private static final LocalDate FIRST_NIGHT = LocalDate.parse("2026-03-10");

	@Autowired
	MutableClock clock;

	@Autowired
	FakePaymentGateway stripe;

	@Autowired
	JdbcTemplate jdbc;

	private String guest;

	private long roomId;

	private long bookingId;

	private String sessionId;

	@BeforeEach
	void startPayingForTwoRooms() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		guest = signUpAndLogIn(aUser().build());
		roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(5).build()));
		bookingId = idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", "2026-03-10", "checkOut", "2026-03-12", "rooms", 2))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
		long asha = api(guest).contentType(ContentType.JSON).body(Map.of("name", "Asha", "age", 30, "gender", "FEMALE"))
			.when().post("/api/v1/users/guests")
			.then().statusCode(201).extract().jsonPath().getLong("id");
		api(guest).contentType(ContentType.JSON).body(Map.of("guestIds", List.of(asha)))
			.when().post("/api/v1/bookings/{id}/guests", bookingId)
			.then().statusCode(200);
		idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", bookingId).then().statusCode(201);
		sessionId = stripe.checkoutsFor(bookingId).getFirst().session().id();
	}

	@Test
	void theSameWebhookDeliveredTwiceConfirmsTheBookingOnce() {
		String paid = checkoutCompleted("evt_paid_" + bookingId, sessionId, "pi_" + bookingId);

		deliver(paid).statusCode(200);
		deliver(paid).statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("CONFIRMED"));
		Map<String, Object> night = jdbc.queryForMap(
				"select booked_count, reserved_count from inventory where room_id = ? and date = ?", roomId, FIRST_NIGHT);
		assertThat(night).containsEntry("booked_count", 2).containsEntry("reserved_count", 0);
		assertThat(jdbc.queryForObject("select count(*) from processed_webhook_events where event_id = ?", Long.class,
				"evt_paid_" + bookingId))
			.isEqualTo(1);
	}

	private ValidatableResponse deliver(String payload) {
		return api().contentType(ContentType.JSON)
			.header("Stripe-Signature", signature(payload, clock.instant()))
			.body(payload)
			.when().post("/webhook/payment")
			.then();
	}

}
