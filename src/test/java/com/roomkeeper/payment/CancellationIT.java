package com.roomkeeper.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.roomkeeper.outbox.OutboxPublisher;
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
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Signature test: a guest cancels a paid booking. The rooms go back on sale at once, and the
 * refund is made exactly once, however often the guest cancels or the refund event is
 * delivered.
 */
@Import(MutableClockConfig.class)
class CancellationIT extends AbstractIntegrationTest {

	/** Ten days before the stay: enough notice for a full refund. */
	private static final Instant BOOKED_AT = Instant.parse("2026-03-10T09:00:00Z");

	private static final LocalDate CHECK_IN = LocalDate.parse("2026-03-20");

	@Autowired
	MutableClock clock;

	@Autowired
	FakePaymentGateway stripe;

	@Autowired
	OutboxPublisher publisher;

	@Autowired
	JdbcTemplate jdbc;

	private String guest;

	private long roomId;

	private long bookingId;

	private String paymentIntentId;

	@BeforeEach
	void payForTwoRooms() {
		clock.setInstant(BOOKED_AT);
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		roomId = firstRoomOf(manager, activeHotel(manager, aHotel().build(), aRoom().withTotalCount(5).build()));
		guest = signUpAndLogIn(aUser().build());
		bookingId = idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", CHECK_IN.toString(), "checkOut", CHECK_IN.plusDays(2).toString(),
					"rooms", 2))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");
		putAGuestOn(guest, bookingId);
		idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", bookingId).then().statusCode(201);
		String sessionId = stripe.checkoutsFor(bookingId).getFirst().session().id();
		paymentIntentId = "pi_cancel_" + bookingId;
		deliverWebhook(checkoutCompleted("evt_paid_" + bookingId, sessionId, paymentIntentId)).statusCode(200);
		assertThat(nightsBooked()).as("paid for").containsOnly(2);
	}

	@Test
	void cancellingRestoresInventoryAndCallsTheRefundExactlyOnce() {
		cancel().statusCode(200).body("status", equalTo("CANCELLED"));
		// A retried request changes nothing more
		cancel().statusCode(200).body("status", equalTo("CANCELLED"));

		assertThat(nightsBooked()).as("the rooms are back on sale").containsOnly(0);

		publisher.run();
		// As if that run had refunded and then died before its commit
		jdbc.update("update outbox_events set published_at = null where type = 'REFUND_DUE' and aggregate_id = ?",
				bookingId);
		publisher.run();
		publisher.run();

		assertThat(stripe.refundsOf(paymentIntentId)).isOne();
		assertThat(jdbc.queryForMap("select status, refunded_amount from payments where booking_id = ? "
				+ "and payment_intent_id = ?", bookingId, paymentIntentId))
			.containsEntry("status", "REFUNDED")
			.extractingByKey("refunded_amount").satisfies(amount -> assertThat(amount.toString())
				.isEqualTo(jdbc.queryForObject("select amount::text from bookings where id = ?", String.class, bookingId)));
	}

	private ValidatableResponse cancel() {
		return api(guest).when().post("/api/v1/bookings/{id}/cancel", bookingId).then();
	}

	private List<Integer> nightsBooked() {
		return jdbc.queryForList("select booked_count from inventory where room_id = ? and date in (?, ?)",
				Integer.class, roomId, CHECK_IN, CHECK_IN.plusDays(1));
	}

}
