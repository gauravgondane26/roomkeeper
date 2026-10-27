package com.roomkeeper.payment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.booking.Booking;
import com.roomkeeper.booking.BookingRepository;
import com.roomkeeper.booking.BookingStatus;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies verified Stripe events, each in one transaction that first records the event's id:
 * a redelivered event finds its id already recorded and changes nothing, so a payment
 * confirms its booking exactly once however often Stripe sends it.
 */
@Service
public class PaymentWebhookService {

	static final String CHECKOUT_COMPLETED = "checkout.session.completed";

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

	private final ProcessedWebhookEvents processedEvents;

	private final PaymentRepository payments;

	private final BookingRepository bookings;

	private final InventoryRepository inventory;

	private final Clock clock;

	public PaymentWebhookService(ProcessedWebhookEvents processedEvents, PaymentRepository payments,
			BookingRepository bookings, InventoryRepository inventory, Clock clock) {
		this.processedEvents = processedEvents;
		this.payments = payments;
		this.bookings = bookings;
		this.inventory = inventory;
		this.clock = clock;
	}

	@Transactional
	public void handle(PaymentEvent event) {
		if (!processedEvents.recordFirstDelivery(event.id(), clock.instant())) {
			log.info("Stripe event {} was already applied; ignoring this delivery", event.id());
			return;
		}
		if (CHECKOUT_COMPLETED.equals(event.type()) && event.paid()) {
			paid(event);
		}
	}

	/** Books the held rooms and confirms the booking. */
	private void paid(PaymentEvent event) {
		Payment payment = payments.findByProviderSessionId(event.sessionId()).orElse(null);
		if (payment == null) {
			// Another environment sharing the Stripe account, or a session from before a reset
			log.warn("Stripe reported a payment for unknown checkout session {}; ignoring it", event.sessionId());
			return;
		}
		if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
			// Another event about a payment already applied (a new event id, not a redelivery)
			return;
		}
		// The booking first, then its nights in date order, as every writer locks them
		Booking booking = bookings.lockById(payment.getBookingId()).orElseThrow();
		if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
			throw new IllegalStateException("Booking " + booking.getId() + " is " + booking.getStatus()
					+ " and can't be confirmed by checkout session " + event.sessionId());
		}
		LocalDate lastNight = booking.getCheckOut().minusDays(1);
		List<Inventory> nights = inventory.lockNights(booking.getRoom().getId(), booking.getCheckIn(), lastNight);
		// The rooms are still held for it, even if the hold's time just ran out: the expiry job
		// hasn't released them, and it can't now while the booking is locked
		nights.forEach(night -> night.confirm(booking.getRoomsCount()));
		booking.confirm();
		payment.succeed(event.paymentIntentId());
	}

}
