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
	void aSessionThatClosesUnpaidExpires() {
		Payment payment = openSession();

		payment.expire();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
		assertThatThrownBy(payment::expire).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aPaymentThatDoesntGoThroughFails() {
		Payment payment = openSession();

		payment.fail();

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThatThrownBy(payment::fail).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void onlyAnOpenSessionCanEndUnpaid() {
		Payment payment = openSession();
		payment.succeed("pi_1");

		assertThatThrownBy(payment::expire).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(payment::fail).isInstanceOf(IllegalStateException.class);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@Test
	void aPaymentTheBookingCantTakeIsRefundedInFull() {
		Payment payment = openSession();

		payment.refund("pi_1", "re_1");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(payment.getRefundedAmount()).isEqualByComparingTo("8000.00");
	}

	@Test
	void aCancellationMakesARefundDueThenItsMade() {
		Payment payment = openSession();
		payment.succeed("pi_1");

		payment.refundDue(new BigDecimal("4000.00"));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
		assertThat(payment.getRefundedAmount()).isEqualByComparingTo("4000.00");

		payment.refund("pi_1", "re_1");

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(payment.getRefundedAmount()).as("the share that was due").isEqualByComparingTo("4000.00");
	}

	@Test
	void allOfAPaymentCanBeDueBack() {
		Payment payment = openSession();
		payment.succeed("pi_1");

		payment.refundDue(new BigDecimal("8000.00"));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
		assertThat(payment.getRefundedAmount()).isEqualByComparingTo("8000.00");
	}

	@Test
	void keepsTheCheckoutSessionItWasOpenedFor() {
		Payment payment = openSession();
		payment.succeed("pi_1");

		assertThat(payment.getBookingId()).isEqualTo(7L);
		assertThat(payment.getProviderSessionId()).isEqualTo("cs_test_1");
		assertThat(payment.getCheckoutUrl()).isEqualTo("https://checkout.stripe.test/c/pay/cs_test_1");
		assertThat(payment.getAmount()).isEqualByComparingTo("8000.00");
		assertThat(payment.getCurrency()).isEqualTo("inr");
		assertThat(payment.getPaymentIntentId()).isEqualTo("pi_1");
	}

	@Test
	void onlyAPaidPaymentCanHaveARefundDue() {
		Payment open = openSession();
		assertThatThrownBy(() -> open.refundDue(new BigDecimal("4000.00"))).isInstanceOf(IllegalStateException.class);

		Payment paid = openSession();
		paid.succeed("pi_1");
		assertThatThrownBy(() -> paid.refundDue(new BigDecimal("8000.01"))).as("more than was paid")
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> paid.refundDue(BigDecimal.ZERO)).as("nothing")
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(paid.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
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
