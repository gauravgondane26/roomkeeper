package com.roomkeeper.payment;

import java.util.EnumSet;
import java.util.Set;

import com.roomkeeper.booking.BookingRepository;
import com.roomkeeper.outbox.OutboxConsumer;
import com.roomkeeper.outbox.OutboxEvent;
import com.roomkeeper.outbox.OutboxEventType;
import com.roomkeeper.outbox.RefundDue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the refunds cancellations made due. The idempotency key is the booking's, and the
 * amount was fixed at cancellation, so however often the event is handed over, Stripe
 * refunds once. Stripe is called before the booking is locked; the payment is recorded
 * REFUNDED in the outbox's transaction, so a failure leaves the event to be retried.
 */
@Component
class CancellationRefunds implements OutboxConsumer {

	private final PaymentGateway gateway;

	private final BookingRepository bookings;

	private final PaymentRepository payments;

	private final JsonMapper json;

	CancellationRefunds(PaymentGateway gateway, BookingRepository bookings, PaymentRepository payments,
			JsonMapper json) {
		this.gateway = gateway;
		this.bookings = bookings;
		this.payments = payments;
		this.json = json;
	}

	@Override
	public String name() {
		return "refunds";
	}

	@Override
	public Set<OutboxEventType> types() {
		return EnumSet.of(OutboxEventType.REFUND_DUE);
	}

	@Override
	public void consume(OutboxEvent event) {
		RefundDue due = json.readValue(event.getPayload(), RefundDue.class);
		String refundId = gateway.refund(due.paymentIntentId(), due.amount(), "cancel-booking-" + due.bookingId());
		// Every change to a payment holds its booking's lock
		bookings.lockById(due.bookingId()).orElseThrow();
		Payment payment = payments.findById(due.paymentId()).orElseThrow();
		if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
			payment.refund(due.paymentIntentId(), refundId);
		}
	}

}
