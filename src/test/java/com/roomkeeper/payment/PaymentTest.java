package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How one payment attempt moves from an open checkout session to its end. */
class PaymentTest {

	@Test
	void aPaidSessionSucceedsOnce() {
		Payment payment = openSession();

		payment.succeed("pi_1");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
		assertThatThrownBy(() -> payment.succeed("pi_1")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aPaymentTheBookingCantTakeIsRefunded() {
		Payment payment = openSession();

		payment.refund("pi_1", "re_1");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
	}

	@Test
	void aPaymentIsRefundedOnlyOnce() {
		Payment payment = openSession();
		payment.refund("pi_1", "re_1");

		assertThatThrownBy(() -> payment.refund("pi_1", "re_2")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> payment.succeed("pi_1")).isInstanceOf(IllegalStateException.class);
	}

	private static Payment openSession() {
		return new Payment(7L, "cs_test_1", "https://checkout.stripe.test/c/pay/cs_test_1", new BigDecimal("8000.00"),
				"inr", Instant.parse("2026-03-10T09:00:00Z"));
	}

}
