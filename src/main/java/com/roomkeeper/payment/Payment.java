package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One attempt to pay for a booking: a Stripe Checkout session for the booking's amount. A
 * booking can have several, one after another (a session that expired, then a new one), but
 * at most one open or paid at a time.
 */
@Entity
@Table(name = "payments")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "booking_id", nullable = false)
	private Long bookingId;

	@Column(name = "provider_session_id", nullable = false, unique = true)
	private String providerSessionId;

	@Column(name = "checkout_url", nullable = false)
	private String checkoutUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentStatus status = PaymentStatus.PENDING;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "payment_intent_id")
	private String paymentIntentId;

	@Column(name = "refund_id")
	private String refundId;

	// Given back, or to be: all of the amount, or a cancellation's share of it
	@Column(name = "refunded_amount", precision = 10, scale = 2)
	private BigDecimal refundedAmount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Payment() {
	}

	/** A new open checkout session for the booking. */
	public Payment(Long bookingId, String providerSessionId, String checkoutUrl, BigDecimal amount, String currency,
			Instant createdAt) {
		this.bookingId = bookingId;
		this.providerSessionId = providerSessionId;
		this.checkoutUrl = checkoutUrl;
		this.amount = amount;
		this.currency = currency;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getBookingId() {
		return bookingId;
	}

	public String getProviderSessionId() {
		return providerSessionId;
	}

	public String getCheckoutUrl() {
		return checkoutUrl;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getPaymentIntentId() {
		return paymentIntentId;
	}

	public BigDecimal getRefundedAmount() {
		return refundedAmount;
	}

	/** Stripe took the payment; refunds go against {@code paymentIntentId}. */
	public void succeed(String paymentIntentId) {
		if (status != PaymentStatus.PENDING) {
			throw new IllegalStateException("Payment " + id + " is " + status + " and can't succeed");
		}
		this.status = PaymentStatus.SUCCEEDED;
		this.paymentIntentId = paymentIntentId;
	}

	/** The checkout session closed unpaid: it ran out, or was closed because the hold did. */
	public void expire() {
		end(PaymentStatus.EXPIRED);
	}

	/** A payment that settles later (a bank transfer, say) didn't go through. */
	public void fail() {
		end(PaymentStatus.FAILED);
	}

	private void end(PaymentStatus unpaid) {
		if (status != PaymentStatus.PENDING) {
			throw new IllegalStateException("Payment " + id + " is " + status + " and can't become " + unpaid);
		}
		this.status = unpaid;
	}

	/**
	 * The booking was cancelled: {@code amount} of the payment is to go back, and will once
	 * the refund is made. Only a paid payment, and no more than was paid.
	 */
	public void refundDue(BigDecimal amount) {
		if (status != PaymentStatus.SUCCEEDED) {
			throw new IllegalStateException("Payment " + id + " is " + status + "; only a paid one can be refunded");
		}
		if (amount.signum() <= 0 || amount.compareTo(this.amount) > 0) {
			throw new IllegalArgumentException("Can't refund " + amount + " of a payment of " + this.amount);
		}
		this.status = PaymentStatus.REFUND_PENDING;
		this.refundedAmount = amount;
	}

	/**
	 * Stripe gave back, against {@code paymentIntentId}, the refund that was due, or all of
	 * the payment if none was (one the booking couldn't take).
	 */
	public void refund(String paymentIntentId, String refundId) {
		if (status == PaymentStatus.REFUNDED) {
			throw new IllegalStateException("Payment " + id + " is already refunded");
		}
		if (refundedAmount == null) {
			refundedAmount = amount;
		}
		this.status = PaymentStatus.REFUNDED;
		this.paymentIntentId = paymentIntentId;
		this.refundId = refundId;
	}

}
