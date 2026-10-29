package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.roomkeeper.outbox.Email;
import com.roomkeeper.outbox.FakeEmailSender;
import com.roomkeeper.outbox.OutboxPublisher;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.FakePaymentGateway;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.TestUser;
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
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.comparesEqualTo;
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

	/** When the stay begins: 2 PM on the check-in date, in the hotel's time zone (UTC). */
	private static final Instant STAY_BEGINS = Instant.parse("2026-03-20T14:00:00Z");

	@Autowired
	MutableClock clock;

	@Autowired
	FakePaymentGateway stripe;

	@Autowired
	OutboxPublisher publisher;

	@Autowired
	FakeEmailSender emails;

	@Autowired
	JdbcTemplate jdbc;

	private TestUser guestUser;

	private String guest;

	private String city;

	private long roomId;

	private long bookingId;

	private String paymentIntentId;

	@BeforeEach
	void payForBothRooms() {
		clock.setInstant(BOOKED_AT);
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		city = "Cancel-" + UUID.randomUUID();
		roomId = firstRoomOf(manager,
				activeHotel(manager, aHotel().inCity(city).build(), aRoom().withTotalCount(2).build()));
		guestUser = aUser().build();
		guest = signUpAndLogIn(guestUser);
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

	@Test
	void theResponseSaysWhatComesBackAndWhenItsBeenMade() {
		String paid = jdbc.queryForObject("select amount::text from bookings where id = ?", String.class, bookingId);

		cancel().statusCode(200)
			.body("bookingId", equalTo((int) bookingId))
			.body("refund", comparesEqualTo(new BigDecimal(paid)))
			.body("currency", equalTo("inr"))
			.body("refundStatus", equalTo("PENDING"));
		publisher.run();

		cancel().statusCode(200).body("refundStatus", equalTo("REFUNDED"));
	}

	@Test
	void withLessThanFortyEightHoursNoticeHalfComesBack() {
		clock.setInstant(STAY_BEGINS.minus(Duration.ofHours(48)).plusSeconds(1));
		guest = logIn(guestUser);

		cancel().statusCode(200).body("status", equalTo("CANCELLED"));
		publisher.run();

		assertThat(stripe.refundedAmountOf(paymentIntentId))
			.isEqualByComparingTo(jdbc.queryForObject("select amount / 2 from bookings where id = ?",
					BigDecimal.class, bookingId));
		assertThat(nightsBooked()).containsOnly(0);
	}

	@Test
	void aStayThatHasBegunCantBeCancelled() {
		clock.setInstant(STAY_BEGINS);
		// The clock moved on days: a fresh access token
		guest = logIn(guestUser);

		cancel().statusCode(409).body("type", equalTo("urn:roomkeeper:problem:cancellation-closed"));

		assertThat(bookingStatus()).isEqualTo("CONFIRMED");
		assertThat(nightsBooked()).containsOnly(2);
	}

	@Test
	void onlyAPaidBookingCanBeCancelled() {
		cancel().statusCode(200);
		long hold = idempotentApi(guest).contentType(ContentType.JSON)
			.body(Map.of("roomId", roomId, "checkIn", CHECK_IN.toString(), "checkOut", CHECK_IN.plusDays(1).toString(),
					"rooms", 1))
			.when().post("/api/v1/bookings/init")
			.then().statusCode(201).extract().jsonPath().getLong("id");

		api(guest).when().post("/api/v1/bookings/{id}/cancel", hold)
			.then().statusCode(409).body("type", equalTo("urn:roomkeeper:problem:booking-state"));
	}

	@Test
	void anotherGuestsBookingIsNotFound() {
		api(signUpAndLogIn(aUser().build())).when().post("/api/v1/bookings/{id}/cancel", bookingId)
			.then().statusCode(404);

		assertThat(bookingStatus()).isEqualTo("CONFIRMED");
	}

	@Test
	void aRefundStripeCantMakeYetIsMadeOnALaterRun() {
		cancel().statusCode(200);
		stripe.failNextRefund();

		publisher.run();

		assertThat(paymentStatus()).isEqualTo("REFUND_PENDING");
		assertThat(stripe.refundsOf(paymentIntentId)).isZero();

		publisher.run();

		assertThat(paymentStatus()).isEqualTo("REFUNDED");
		assertThat(stripe.refundsOf(paymentIntentId)).isOne();
	}

	@Test
	void theGuestIsEmailedAboutTheCancellation() {
		cancel().statusCode(200);

		publisher.run();

		assertThat(emails.sentTo(guestUser.email())).extracting(Email::subject)
			.contains("Your booking " + bookingId + " is cancelled");
	}

	@Test
	void cancellingPutsASoldOutHotelBackInSearch() {
		await().untilAsserted(() -> searchFinds(0));

		cancel().statusCode(200);

		await().untilAsserted(() -> searchFinds(1));
	}

	private void searchFinds(int hotels) {
		api().queryParam("city", city).queryParam("checkIn", CHECK_IN.toString())
			.queryParam("checkOut", CHECK_IN.plusDays(2).toString())
			.when().get("/api/v1/hotels/search")
			.then().statusCode(200).body("totalHotels", equalTo(hotels));
	}

	private String bookingStatus() {
		return jdbc.queryForObject("select status from bookings where id = ?", String.class, bookingId);
	}

	private String paymentStatus() {
		return jdbc.queryForObject("select status from payments where payment_intent_id = ?", String.class,
				paymentIntentId);
	}

	private ValidatableResponse cancel() {
		return api(guest).when().post("/api/v1/bookings/{id}/cancel", bookingId).then();
	}

	private List<Integer> nightsBooked() {
		return jdbc.queryForList("select booked_count from inventory where room_id = ? and date in (?, ?)",
				Integer.class, roomId, CHECK_IN, CHECK_IN.plusDays(1));
	}

}
