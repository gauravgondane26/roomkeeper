package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Instant;

import com.roomkeeper.testsupport.AbstractDataTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payments tables: the entity maps onto them, and the constraints refuse inconsistent
 * payments even when written with plain SQL, past every check in the app.
 */
class PaymentSchemaIT extends AbstractDataTest {

	private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

	@Autowired
	private PaymentRepository payments;

	@Autowired
	private EntityManager entityManager;

	private long bookingId;

	@BeforeEach
	void createABooking() {
		long roomId = insertRoom(insertHotel(insertManager(), "Goa", "Asia/Kolkata", true));
		bookingId = jdbc.queryForObject("""
				insert into bookings (user_id, room_id, check_in, check_out, rooms_count, status, amount,
				                      hold_expires_at, created_at)
				values (?, ?, '2026-12-24', '2026-12-26', 1, 'PAYMENT_PENDING', 8000.00,
				        now() + interval '10 minutes', now()) returning id""",
				Long.class, insertGuestUser(), roomId);
	}

	@Test
	void savesAnOpenPaymentAndReadsItBack() {
		long id = payments.save(new Payment(bookingId, "cs_test_1", "https://checkout.stripe.com/c/pay/cs_test_1",
				new BigDecimal("8000.00"), "inr", NOW)).getId();
		entityManager.flush();
		entityManager.clear();

		Payment payment = payments.findById(id).orElseThrow();

		assertThat(payment.getBookingId()).isEqualTo(bookingId);
		assertThat(payment.getProviderSessionId()).isEqualTo("cs_test_1");
		assertThat(payment.getCheckoutUrl()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getAmount()).isEqualByComparingTo("8000.00");
		assertThat(payment.getCurrency()).isEqualTo("inr");
	}

	@Test
	void allowsOneOpenCheckoutSessionPerBooking() {
		insertPayment("cs_test_1", "PENDING", null, null);

		assertThatThrownBy(() -> insertPayment("cs_test_2", "PENDING", null, null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_one_live_per_booking_idx");
	}

	@Test
	void neverPaysForABookingTwice() {
		insertPayment("cs_test_1", "SUCCEEDED", "pi_test_1", null);

		assertThatThrownBy(() -> insertPayment("cs_test_2", "SUCCEEDED", "pi_test_2", null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_one_live_per_booking_idx");
	}

	@Test
	void allowsANewAttemptAfterAnExpiredOrFailedOne() {
		insertPayment("cs_test_1", "EXPIRED", null, null);
		insertPayment("cs_test_2", "FAILED", null, null);

		insertPayment("cs_test_3", "PENDING", null, null);

		assertThat(jdbc.queryForObject("select count(*) from payments where booking_id = ?", Long.class, bookingId))
			.isEqualTo(3);
	}

	@Test
	void rejectsTheSameCheckoutSessionTwice() {
		insertPayment("cs_test_1", "EXPIRED", null, null);

		assertThatThrownBy(() -> insertPayment("cs_test_1", "PENDING", null, null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_provider_session_id_key");
	}

	@Test
	void rejectsAPaidPaymentWithoutItsPaymentIntent() {
		assertThatThrownBy(() -> insertPayment("cs_test_1", "SUCCEEDED", null, null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_paid_has_payment_intent");
	}

	@Test
	void rejectsARefundWithoutItsRefundId() {
		assertThatThrownBy(() -> insertPayment("cs_test_1", "REFUNDED", "pi_test_1", null, new BigDecimal("8000.00")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_refunded_has_refund_id");
	}

	@Test
	void recordsARefundOfPartOfThePayment() {
		insertPayment("cs_test_1", "REFUND_PENDING", "pi_test_1", null, new BigDecimal("4000.00"));
		jdbc.update("update payments set status = 'REFUNDED', refund_id = 're_test_1' where booking_id = ?", bookingId);

		assertThat(jdbc.queryForObject("select refunded_amount from payments where booking_id = ?", BigDecimal.class,
				bookingId))
			.isEqualByComparingTo("4000.00");
	}

	@Test
	void rejectsARefundDueWithoutItsAmount() {
		assertThatThrownBy(() -> insertPayment("cs_test_1", "REFUND_PENDING", "pi_test_1", null, null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_refund_has_amount");
	}

	@Test
	void rejectsRefundingMoreThanWasPaid() {
		assertThatThrownBy(
				() -> insertPayment("cs_test_1", "REFUNDED", "pi_test_1", "re_test_1", new BigDecimal("8000.01")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_refund_within_amount");
	}

	@Test
	void rejectsARefundDueWithoutThePaymentIntent() {
		assertThatThrownBy(() -> insertPayment("cs_test_1", "REFUND_PENDING", null, null, new BigDecimal("4000.00")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_paid_has_payment_intent");
	}

	@Test
	void rejectsAnUnknownStatus() {
		assertThatThrownBy(() -> insertPayment("cs_test_1", "PAID", "pi_test_1", null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_status_check");
	}

	@Test
	void keepsABookingWithPayments() {
		insertPayment("cs_test_1", "PENDING", null, null);

		assertThatThrownBy(() -> jdbc.update("delete from bookings where id = ?", bookingId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("payments_booking_id_fkey");
	}

	@Test
	void recordsAWebhookEventOnlyOnce() {
		jdbc.update("insert into processed_webhook_events (event_id, received_at) values ('evt_1', now())");

		assertThatThrownBy(() -> jdbc.update(
				"insert into processed_webhook_events (event_id, received_at) values ('evt_1', now())"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("processed_webhook_events_pkey");
	}

	private void insertPayment(String sessionId, String status, String paymentIntentId, String refundId) {
		insertPayment(sessionId, status, paymentIntentId, refundId, null);
	}

	/** A payment of 8000.00, with this much refunded or due back. */
	private void insertPayment(String sessionId, String status, String paymentIntentId, String refundId,
			BigDecimal refundedAmount) {
		jdbc.update("""
				insert into payments (booking_id, provider_session_id, checkout_url, status, amount, currency,
				                      payment_intent_id, refund_id, refunded_amount, created_at)
				values (?, ?, 'https://checkout.stripe.com/c/pay/' || ?, ?, 8000.00, 'inr', ?, ?, ?, now())""",
				bookingId, sessionId, sessionId, status, paymentIntentId, refundId, refundedAmount);
	}

}
