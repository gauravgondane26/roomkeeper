package com.roomkeeper.payment;

import com.roomkeeper.booking.BookingExpiredEvent;
import com.roomkeeper.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Closes the open checkout session of a booking whose hold ran out while the guest was
 * paying. Stripe keeps a session open for at least 30 minutes, longer than a hold, so
 * without this a guest could pay for rooms already back on sale. Runs after the release
 * commits, off the expiry job's thread, so a slow Stripe never holds up releasing rooms. A
 * payment that gets through anyway (made as the session closed, or while Stripe couldn't be
 * reached) is confirmed or refunded by the webhook.
 */
@Component
class CheckoutSessionCloser {

	private static final Logger log = LoggerFactory.getLogger(CheckoutSessionCloser.class);

	private final PaymentRepository payments;

	private final PaymentGateway gateway;

	CheckoutSessionCloser(PaymentRepository payments, PaymentGateway gateway) {
		this.payments = payments;
		this.gateway = gateway;
	}

	@Async(PaymentGatewayConfig.CLOSING_EXECUTOR)
	@TransactionalEventListener(condition = "#event.paymentStarted()")
	void close(BookingExpiredEvent event) {
		payments.findByBookingIdAndStatus(event.bookingId(), PaymentStatus.PENDING).ifPresent(payment -> {
			try {
				gateway.expireCheckoutSession(payment.getProviderSessionId());
			}
			catch (ApiException ex) {
				log.warn("Couldn't close checkout session {} of expired booking {}; it closes on its own later",
						payment.getProviderSessionId(), event.bookingId());
			}
		});
	}

}
