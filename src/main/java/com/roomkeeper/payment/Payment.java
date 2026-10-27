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

	/** Stripe took the payment; refunds go against {@code paymentIntentId}. */
	public void succeed(String paymentIntentId) {
		if (status != PaymentStatus.PENDING) {
			throw new IllegalStateException("Payment " + id + " is " + status + " and can't succeed");
		}
		this.status = PaymentStatus.SUCCEEDED;
		this.paymentIntentId = paymentIntentId;
	}

}
