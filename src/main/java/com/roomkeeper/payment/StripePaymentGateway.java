package com.roomkeeper.payment;

import java.math.BigDecimal;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.ProductData;
import com.stripe.param.checkout.SessionCreateParams.PaymentIntentData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stripe, through its Java SDK. Checkout sessions and refunds are sent with an idempotency
 * key, so the SDK's own retries (and ours) never charge or refund twice.
 */
class StripePaymentGateway implements PaymentGateway {

	private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

	private final StripeClient stripe;

	private final PaymentProperties properties;

	StripePaymentGateway(StripeClient stripe, PaymentProperties properties) {
		this.stripe = stripe;
		this.properties = properties;
	}

	@Override
	public CheckoutSession createCheckoutSession(CheckoutRequest request) {
		try {
			Session session = stripe.v1().checkout().sessions().create(checkoutParams(request, properties),
					RequestOptions.builder().setIdempotencyKey(request.idempotencyKey()).build());
			return new CheckoutSession(session.getId(), session.getUrl());
		}
		catch (StripeException ex) {
			throw unavailable("open a checkout session for booking " + request.bookingId(), ex);
		}
	}

	@Override
	public void expireCheckoutSession(String sessionId) {
		try {
			stripe.v1().checkout().sessions().expire(sessionId);
		}
		catch (StripeException ex) {
			throw unavailable("expire checkout session " + sessionId, ex);
		}
	}

	@Override
	public String refund(String paymentIntentId, String idempotencyKey) {
		try {
			return stripe.v1()
				.refunds()
				.create(RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
						RequestOptions.builder().setIdempotencyKey(idempotencyKey).build())
				.getId();
		}
		catch (StripeException ex) {
			throw unavailable("refund payment " + paymentIntentId, ex);
		}
	}

	/** One line item for the booking's frozen amount, tied to the booking both ways. */
	static SessionCreateParams checkoutParams(CheckoutRequest request, PaymentProperties properties) {
		String bookingId = String.valueOf(request.bookingId());
		return SessionCreateParams.builder()
			.setMode(SessionCreateParams.Mode.PAYMENT)
			.addLineItem(LineItem.builder()
				.setQuantity(1L)
				.setPriceData(PriceData.builder()
					.setCurrency(properties.currency())
					.setUnitAmount(minorUnits(request.amount()))
					.setProductData(ProductData.builder().setName(request.description()).build())
					.build())
				.build())
			// The webhook finds the booking from the session, and refunds from the payment
			.setClientReferenceId(bookingId)
			.putMetadata("booking_id", bookingId)
			.setPaymentIntentData(PaymentIntentData.builder().putMetadata("booking_id", bookingId).build())
			.setExpiresAt(request.expiresAt().getEpochSecond())
			.setSuccessUrl(properties.successUrl())
			.setCancelUrl(properties.cancelUrl())
			.build();
	}

	/** Stripe takes amounts in the currency's smallest unit: 8000.00 rupees is 800000 paise. */
	static long minorUnits(BigDecimal amount) {
		return amount.movePointRight(2).longValueExact();
	}

	private static ApiException unavailable(String action, StripeException ex) {
		log.warn("Stripe couldn't {}", action, ex);
		return new ApiException(ProblemType.PAYMENTS_UNAVAILABLE, "The payment provider couldn't " + action
				+ " just now; try again shortly");
	}

}
