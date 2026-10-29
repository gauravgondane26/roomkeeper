package com.roomkeeper.payment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.roomkeeper.booking.HoldExpiryJob;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.FakePaymentGateway;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.StripeWebhooks.checkoutCompleted;
import static com.roomkeeper.testsupport.StripeWebhooks.checkoutEvent;
import static com.roomkeeper.testsupport.StripeWebhooks.signature;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
	HoldExpiryJob job;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

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
		putAGuestOn(guest, bookingId);
		idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", bookingId).then().statusCode(201);
		sessionId = stripe.checkoutsFor(bookingId).getFirst().session().id();
	}

	@Test
	void theSameWebhookDeliveredTwiceConfirmsTheBookingOnceAndCreatesOneOutboxRow() {
		String paid = checkoutCompleted("evt_paid_" + bookingId, sessionId, "pi_" + bookingId);

		deliverWebhook(paid).statusCode(200);
		deliverWebhook(paid).statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("CONFIRMED"));
		Map<String, Object> night = jdbc.queryForMap(
				"select booked_count, reserved_count from inventory where room_id = ? and date = ?", roomId, FIRST_NIGHT);
		assertThat(night).containsEntry("booked_count", 2).containsEntry("reserved_count", 0);
		assertThat(jdbc.queryForObject("select count(*) from processed_webhook_events where event_id = ?", Long.class,
				"evt_paid_" + bookingId))
			.isEqualTo(1);
		// The guest's confirmation goes out once, however often Stripe reports the payment
		assertThat(confirmationsInTheOutbox()).isEqualTo(1);
	}

	@Test
	void aPaidCheckoutConfirmsTheBookingAndRecordsThePayment() {
		deliverWebhook(checkoutCompleted("evt_" + bookingId, sessionId, "pi_" + bookingId)).statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}", bookingId).then().body("status", equalTo("CONFIRMED"));
		assertThat(jdbc.queryForMap("select status, payment_intent_id from payments where provider_session_id = ?",
				sessionId))
			.containsEntry("status", "SUCCEEDED")
			.containsEntry("payment_intent_id", "pi_" + bookingId);
		assertThat(nightsBooked()).containsOnly(2);
	}

	@Test
	void aPaymentJustAfterTheHoldRanOutConfirmsBeforeTheRoomsAreReleased() {
		// The expiry job hasn't run yet, so the rooms are still held for the booking
		clock.advance(Duration.ofMinutes(10));

		deliverWebhook(checkoutCompleted("evt_just_late_" + bookingId, sessionId, "pi_" + bookingId)).statusCode(200);
		job.run();

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("CONFIRMED"));
		assertThat(nightsBooked()).containsOnly(2);
		assertThat(stripe.wasExpired(sessionId)).isFalse();
	}

	@Test
	void aDeliveryStripeDidntSignChangesNothing() {
		String forged = checkoutCompleted("evt_forged_" + bookingId, sessionId, "pi_forged");

		api().contentType(ContentType.JSON)
			.header("Stripe-Signature", signature(forged, clock.instant(), "whsec_not_stripes"))
			.body(forged)
			.when().post("/webhook/payment")
			.then()
				.statusCode(400)
				.body("type", equalTo("urn:roomkeeper:problem:invalid-webhook-signature"));
		api().contentType(ContentType.JSON).body(forged)
			.when().post("/webhook/payment")
			.then().statusCode(400);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("PAYMENT_PENDING"));
		// Not recorded either, so the real event still applies when it comes
		assertThat(jdbc.queryForObject("select count(*) from processed_webhook_events where event_id = ?", Long.class,
				"evt_forged_" + bookingId))
			.isZero();
	}

	@Test
	void deliveriesOfOneEventRacingEachOtherConfirmOnce() throws Exception {
		String paid = checkoutCompleted("evt_racing_" + bookingId, sessionId, "pi_" + bookingId);
		List<Callable<Integer>> deliveries = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			deliveries.add(() -> deliverWebhook(paid).extract().statusCode());
		}

		assertThat(runTogether(deliveries)).containsOnly(200);

		assertThat(nightsBooked()).containsOnly(2);
	}

	@Test
	void deliveriesInFlightTogetherConfirmOnce() throws Exception {
		// Another connection holds the booking, so two deliveries of one event are both in flight
		// before either applies it: the first waits for the booking, the second for the first's
		// record of the event
		String paid = checkoutCompleted("evt_overlap_" + bookingId, sessionId, "pi_" + bookingId);
		// Threads of their own: on a 2-CPU machine (CI) the common pool has one worker, which
		// would run the second delivery only after the first finished
		try (ExecutorService pool = Executors.newFixedThreadPool(2);
				Connection other = dataSource.getConnection()) {
			other.setAutoCommit(false);
			try (PreparedStatement lock = other.prepareStatement("select id from bookings where id = ? for update")) {
				lock.setLong(1, bookingId);
				lock.executeQuery();
			}
			List<Future<Integer>> deliveries = List.of(pool.submit(() -> deliverWebhook(paid).extract().statusCode()),
					pool.submit(() -> deliverWebhook(paid).extract().statusCode()));
			await().atMost(Duration.ofSeconds(10)).until(() -> queriesWaitingOnLocks() == 2);
			other.rollback();

			for (Future<Integer> delivery : deliveries) {
				assertThat(delivery.get(10, TimeUnit.SECONDS)).isEqualTo(200);
			}
		}
		assertThat(nightsBooked()).containsOnly(2);
	}

	@Test
	void anotherEventAboutAnAppliedPaymentChangesNothing() {
		deliverWebhook(checkoutCompleted("evt_first_" + bookingId, sessionId, "pi_" + bookingId)).statusCode(200);

		deliverWebhook(checkoutCompleted("evt_second_" + bookingId, sessionId, "pi_" + bookingId)).statusCode(200);

		assertThat(nightsBooked()).containsOnly(2);
	}

	@Test
	void aCheckoutNotYetPaidConfirmsNothing() {
		// Completed with a payment method that settles later (a bank transfer, say)
		deliverWebhook(checkoutEvent("evt_unpaid_" + bookingId, "checkout.session.completed", sessionId, "unpaid", null))
			.statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("PAYMENT_PENDING"));
		assertThat(nightsBooked()).containsOnly(0);
	}

	@Test
	void aDelayedPaymentThatGoesThroughConfirmsTheBooking() {
		deliverWebhook(checkoutEvent("evt_unpaid_" + bookingId, "checkout.session.completed", sessionId, "unpaid", null))
			.statusCode(200);

		deliverWebhook(checkoutEvent("evt_settled_" + bookingId, "checkout.session.async_payment_succeeded", sessionId,
				"paid", "pi_" + bookingId))
			.statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("CONFIRMED"));
		assertThat(paymentStatus(sessionId)).isEqualTo("SUCCEEDED");
		assertThat(nightsBooked()).containsOnly(2);
	}

	@Test
	void anExpiredSessionLetsTheGuestPayAgainWhileTheRoomsAreHeld() {
		deliverWebhook(checkoutEvent("evt_expired_" + bookingId, "checkout.session.expired", sessionId, "unpaid", null))
			.statusCode(200);

		assertThat(paymentStatus(sessionId)).isEqualTo("EXPIRED");
		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("PAYMENT_PENDING"));
		assertTheGuestCanPayAgain();
	}

	@Test
	void aFailedPaymentLetsTheGuestPayAgainWhileTheRoomsAreHeld() {
		// Completed with a payment method that settles later, which then didn't go through
		deliverWebhook(checkoutEvent("evt_unpaid_" + bookingId, "checkout.session.completed", sessionId, "unpaid", null))
			.statusCode(200);

		deliverWebhook(checkoutEvent("evt_failed_" + bookingId, "checkout.session.async_payment_failed", sessionId,
				"unpaid", null))
			.statusCode(200);

		assertThat(paymentStatus(sessionId)).isEqualTo("FAILED");
		assertThat(nightsBooked()).containsOnly(0);
		assertTheGuestCanPayAgain();
	}

	@Test
	void aSessionReportedClosedAfterItWasPaidStaysPaid() {
		// Stripe doesn't promise to deliver events in order
		deliverWebhook(checkoutCompleted("evt_paid_" + bookingId, sessionId, "pi_" + bookingId)).statusCode(200);

		deliverWebhook(checkoutEvent("evt_expired_" + bookingId, "checkout.session.expired", sessionId, "unpaid", null))
			.statusCode(200);

		assertThat(paymentStatus(sessionId)).isEqualTo("SUCCEEDED");
		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("CONFIRMED"));
	}

	@Test
	void aPaymentOnASessionAlreadyClosedIsRefunded() {
		// Stripe never takes payment on an expired session; if it ever reports one, the money goes back
		deliverWebhook(checkoutEvent("evt_expired_" + bookingId, "checkout.session.expired", sessionId, "unpaid", null))
			.statusCode(200);

		deliverWebhook(checkoutCompleted("evt_paid_" + bookingId, sessionId, "pi_" + bookingId)).statusCode(200);

		assertThat(paymentStatus(sessionId)).isEqualTo("REFUNDED");
		assertThat(stripe.refundsOf("pi_" + bookingId)).isOne();
		// The rooms are still held, for the guest to pay for again
		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("PAYMENT_PENDING"));
		assertThat(nightsBooked()).containsOnly(0);
	}

	@Test
	void eventsAboutOtherThingsAreAcknowledgedAndIgnored() {
		deliverWebhook(
				checkoutEvent("evt_other_" + bookingId, "payment_intent.created", "pi_" + bookingId, "unpaid", null))
			.statusCode(200);
		deliverWebhook(checkoutCompleted("evt_unknown_" + bookingId, "cs_test_not_ours", "pi_not_ours")).statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("PAYMENT_PENDING"));
	}

	/** A new checkout session for the booking, then paying on it confirms the booking. */
	private void assertTheGuestCanPayAgain() {
		idempotentApi(guest).when().post("/api/v1/bookings/{id}/payments", bookingId).then().statusCode(201);
		List<String> sessions = stripe.checkoutsFor(bookingId).stream().map(checkout -> checkout.session().id()).toList();
		assertThat(sessions).hasSize(2).doesNotHaveDuplicates();
		String secondSession = sessions.getLast();
		assertThat(paymentStatus(secondSession)).isEqualTo("PENDING");

		deliverWebhook(checkoutCompleted("evt_second_" + bookingId, secondSession, "pi_second_" + bookingId))
			.statusCode(200);

		api(guest).when().get("/api/v1/bookings/{id}/status", bookingId)
			.then().body("status", equalTo("CONFIRMED"));
		assertThat(nightsBooked()).containsOnly(2);
	}

	private String paymentStatus(String session) {
		return jdbc.queryForObject("select status from payments where provider_session_id = ?", String.class, session);
	}

	private long confirmationsInTheOutbox() {
		return jdbc.queryForObject(
				"select count(*) from outbox_events where type = 'BOOKING_CONFIRMED' and aggregate_id = ?", Long.class,
				bookingId);
	}

	private long queriesWaitingOnLocks() {
		return jdbc.queryForObject("""
				select count(*) from pg_stat_activity
				where datname = current_database() and wait_event_type = 'Lock'""", Long.class);
	}

	private List<Integer> nightsBooked() {
		return jdbc.queryForList("select booked_count from inventory where room_id = ? and date in (?, ?)",
				Integer.class, roomId, FIRST_NIGHT, FIRST_NIGHT.plusDays(1));
	}

}
