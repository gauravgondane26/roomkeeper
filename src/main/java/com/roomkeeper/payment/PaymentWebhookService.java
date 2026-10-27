package com.roomkeeper.payment;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.roomkeeper.booking.Booking;
import com.roomkeeper.booking.BookingRepository;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies verified Stripe events, each in one transaction that first records the event's id:
 * a redelivered event finds its id already recorded and changes nothing, so a payment
 * confirms its booking, or is refunded, exactly once however often Stripe sends it.
 * <p>
 * A payment that arrives after its booking's hold ran out and was released books the same
 * rooms again if they're still on sale. A payment the booking can't take is refunded in
 * full: Stripe is called outside the transaction, never with the booking or its nights
 * locked, and the event is recorded only once the refund is made. Until then Stripe keeps
 * redelivering it, and the refund's idempotency key means no delivery refunds twice.
 * <p>
 * A session that closes unpaid, or whose delayed payment fails, ends its payment; while the
 * rooms are still held the guest can open a new session.
 */
@Service
public class PaymentWebhookService {

	static final String CHECKOUT_COMPLETED = "checkout.session.completed";

	/** A completed checkout's delayed payment (a bank transfer, say) went through... */
	static final String ASYNC_PAYMENT_SUCCEEDED = "checkout.session.async_payment_succeeded";

	/** ...or didn't. */
	static final String ASYNC_PAYMENT_FAILED = "checkout.session.async_payment_failed";

	/** The session closed unpaid: it ran out, or we closed it because the hold ran out. */
	static final String CHECKOUT_EXPIRED = "checkout.session.expired";

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

	private final ProcessedWebhookEvents processedEvents;

	private final PaymentRepository payments;

	private final BookingRepository bookings;

	private final InventoryRepository inventory;

	private final PaymentGateway gateway;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public PaymentWebhookService(ProcessedWebhookEvents processedEvents, PaymentRepository payments,
			BookingRepository bookings, InventoryRepository inventory, PaymentGateway gateway,
			ApplicationEventPublisher events, TransactionTemplate transaction, Clock clock) {
		this.processedEvents = processedEvents;
		this.payments = payments;
		this.bookings = bookings;
		this.inventory = inventory;
		this.gateway = gateway;
		this.events = events;
		this.transaction = transaction;
		this.clock = clock;
	}

	/** A payment Stripe took that its booking can't take, to give back in full. */
	private record Refund(long bookingId, long paymentId, String paymentIntentId) {
	}

	/** A checkout session's payment, and its booking, locked. */
	private record Locked(Booking booking, Payment payment) {
	}

	public void handle(PaymentEvent event) {
		Refund refund = transaction.execute(status -> {
			Refund owed = apply(event);
			if (owed != null) {
				// The event is recorded with the refund instead, once Stripe has made it
				status.setRollbackOnly();
			}
			return owed;
		});
		if (refund != null) {
			refund(event, refund);
		}
	}

	/** Applies the event, or returns the refund it calls for. */
	private Refund apply(PaymentEvent event) {
		if (!processedEvents.recordFirstDelivery(event.id(), clock.instant())) {
			log.info("Stripe event {} was already applied; ignoring this delivery", event.id());
			return null;
		}
		return switch (event.type()) {
			case CHECKOUT_COMPLETED, ASYNC_PAYMENT_SUCCEEDED -> event.paid() ? paid(event) : null;
			case CHECKOUT_EXPIRED -> {
				unpaid(event, Payment::expire);
				yield null;
			}
			case ASYNC_PAYMENT_FAILED -> {
				unpaid(event, Payment::fail);
				yield null;
			}
			case null, default -> null;
		};
	}

	/**
	 * The session's payment, read once its booking is locked: the booking first, then its
	 * nights in date order, as every writer locks them, and every change to a payment holds
	 * its booking's lock. Empty for a session that isn't ours.
	 */
	private Optional<Locked> lock(PaymentEvent event) {
		Long bookingId = payments.findBookingIdBySessionId(event.sessionId()).orElse(null);
		if (bookingId == null) {
			// Another environment sharing the Stripe account, a session from before a reset, or
			// one closed straight away because the hold ran out while it was being opened
			log.warn("Stripe reported on unknown checkout session {}; ignoring it", event.sessionId());
			return Optional.empty();
		}
		Booking booking = bookings.lockById(bookingId).orElseThrow();
		return Optional.of(new Locked(booking, payments.findByProviderSessionId(event.sessionId()).orElseThrow()));
	}

	/** Books the rooms and confirms the booking, or returns the refund owed if it can't take the payment. */
	private Refund paid(PaymentEvent event) {
		Locked locked = lock(event).orElse(null);
		if (locked == null) {
			return null;
		}
		Booking booking = locked.booking();
		Payment payment = locked.payment();
		if (payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.REFUNDED) {
			// Another event about a payment already applied (a new event id, not a redelivery)
			return null;
		}
		boolean taken = false;
		if (payment.getStatus() == PaymentStatus.PENDING) {
			taken = switch (booking.getStatus()) {
				case PAYMENT_PENDING -> confirmHeld(booking);
				case EXPIRED -> bookAgain(booking);
				// No other status can take a payment
				default -> false;
			};
		}
		if (taken) {
			payment.succeed(event.paymentIntentId());
			return null;
		}
		log.warn("Booking {} is {} and can't take the payment from checkout session {} ({}); refunding it",
				booking.getId(), booking.getStatus(), event.sessionId(), payment.getStatus());
		return new Refund(booking.getId(), payment.getId(), event.paymentIntentId());
	}

	/** Ends the session's payment unpaid, unless it was paid or ended already. */
	private void unpaid(PaymentEvent event, Consumer<Payment> end) {
		lock(event).map(Locked::payment)
			// Stripe doesn't promise to deliver events in order
			.filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
			.ifPresent(end);
	}

	/** Books the rooms still held for the booking and confirms it. */
	private boolean confirmHeld(Booking booking) {
		LocalDate lastNight = booking.getCheckOut().minusDays(1);
		List<Inventory> nights = inventory.lockNights(booking.getRoom().getId(), booking.getCheckIn(), lastNight);
		// The rooms are still held for it, even if the hold's time just ran out: the expiry job
		// hasn't released them, and it can't now while the booking is locked
		nights.forEach(night -> night.confirm(booking.getRoomsCount()));
		booking.confirm();
		return true;
	}

	/**
	 * For a booking whose hold ran out and was released: books the same rooms again, at the
	 * price the guest paid, and confirms it. False if they aren't on sale now, as init would
	 * sell them: the hotel on sale, the stay not yet begun, and every night open with the
	 * rooms free.
	 */
	private boolean bookAgain(Booking booking) {
		Room room = booking.getRoom();
		Hotel hotel = room.getHotel();
		if (!hotel.isActive() || booking.getCheckIn().isBefore(hotel.today(clock))) {
			return false;
		}
		int count = booking.getRoomsCount();
		LocalDate lastNight = booking.getCheckOut().minusDays(1);
		List<Inventory> nights = inventory.lockNights(room.getId(), booking.getCheckIn(), lastNight);
		boolean everyNight = nights.size() == ChronoUnit.DAYS.between(booking.getCheckIn(), booking.getCheckOut());
		if (!everyNight || nights.stream().anyMatch(night -> !night.canSell(count))) {
			return false;
		}
		nights.forEach(night -> night.book(count));
		booking.confirm();
		boolean soldOut = nights.stream().anyMatch(night -> night.getAvailable() == 0);
		events.publishEvent(
				InventoryChangedEvent.sale(hotel.getId(), hotel.getCity(), booking.getCheckIn(), lastNight, soldOut));
		return true;
	}

	/** Refunds the payment, then records the event and the refund together. */
	private void refund(PaymentEvent event, Refund refund) {
		// One key per payment, so however many deliveries and events get here, Stripe refunds it once
		String refundId = gateway.refund(refund.paymentIntentId(), "refund-payment-" + refund.paymentId());
		transaction.executeWithoutResult(status -> {
			if (!processedEvents.recordFirstDelivery(event.id(), clock.instant())) {
				// A delivery of this event racing this one recorded it first
				return;
			}
			bookings.lockById(refund.bookingId()).orElseThrow();
			Payment payment = payments.findById(refund.paymentId()).orElseThrow();
			// Another event about the payment may have recorded the same refund first
			if (payment.getStatus() != PaymentStatus.REFUNDED) {
				payment.refund(refund.paymentIntentId(), refundId);
			}
		});
		log.info("Refunded payment {} of booking {} ({})", refund.paymentId(), refund.bookingId(), refundId);
	}

}
