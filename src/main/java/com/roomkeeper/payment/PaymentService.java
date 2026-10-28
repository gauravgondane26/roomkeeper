package com.roomkeeper.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.roomkeeper.booking.Booking;
import com.roomkeeper.booking.BookingNotFoundException;
import com.roomkeeper.booking.BookingRepository;
import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.hotel.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts paying for a booking with a Stripe Checkout session for its frozen amount.
 * <p>
 * Stripe is called between two short transactions, never while the booking is locked: the
 * first checks the booking and settles what to ask for, the second locks it again, checks
 * nothing changed meanwhile, and records the session. Requests racing for one booking ask
 * Stripe with the same idempotency key and identical parameters (the session's expiry comes
 * from the hold, not the clock), so they get the same session back.
 */
@Service
public class PaymentService {

	/** Past the hold's end: Stripe keeps a checkout session open for at least 30 minutes. */
	private static final Duration SESSION_PAST_HOLD = Duration.ofMinutes(31);

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final BookingRepository bookings;

	private final PaymentRepository payments;

	private final PaymentGateway gateway;

	private final PaymentMapper mapper;

	private final PaymentProperties properties;

	private final TransactionTemplate transaction;

	private final Clock clock;

	public PaymentService(BookingRepository bookings, PaymentRepository payments, PaymentGateway gateway,
			PaymentMapper mapper, PaymentProperties properties, TransactionTemplate transaction, Clock clock) {
		this.bookings = bookings;
		this.payments = payments;
		this.gateway = gateway;
		this.mapper = mapper;
		this.properties = properties;
		this.transaction = transaction;
		this.clock = clock;
	}

	/** A payment for a booking, and whether this request opened it (or found it already open). */
	public record StartedPayment(PaymentResponse payment, boolean opened) {
	}

	/** What to ask Stripe for, unless the booking already has an open session. */
	private record Plan(StartedPayment alreadyOpen, CheckoutRequest checkout) {
	}

	/** Opens a checkout session for the user's booking, or returns the one already open. */
	public StartedPayment start(long userId, long bookingId) {
		Plan plan = transaction.execute(status -> plan(userId, bookingId));
		if (plan.alreadyOpen() != null) {
			return plan.alreadyOpen();
		}
		CheckoutSession session = gateway.createCheckoutSession(plan.checkout());
		try {
			return transaction.execute(status -> record(userId, bookingId, plan.checkout(), session));
		}
		catch (ApiException ex) {
			// The hold ran out, or the booking changed, while Stripe was called: nobody may pay now
			closeQuietly(session);
			throw ex;
		}
	}

	private Plan plan(long userId, long bookingId) {
		Booking booking = bookings.findByIdAndUserId(bookingId, userId)
			.orElseThrow(() -> new BookingNotFoundException(bookingId));
		// Count before looking for an open payment: a racing request's payment committed between
		// the two queries is then found open, rather than counted as an attempt of its own
		long attempt = payments.countByBookingId(bookingId) + 1;
		Optional<Payment> open = payments.findByBookingIdAndStatus(bookingId, PaymentStatus.PENDING);
		if (open.isPresent()) {
			return new Plan(new StartedPayment(mapper.toResponse(open.get()), false), null);
		}
		booking.requirePaymentCanStart(clock.instant());
		Instant expiresAt = booking.getHoldExpiresAt().plus(SESSION_PAST_HOLD).truncatedTo(ChronoUnit.SECONDS);
		return new Plan(null, new CheckoutRequest(bookingId, booking.getAmount(), describe(booking), expiresAt,
				"booking-" + bookingId + "-checkout-" + attempt));
	}

	private StartedPayment record(long userId, long bookingId, CheckoutRequest checkout, CheckoutSession session) {
		// Locked, so the expiry job and other requests for this booking wait their turn
		Booking booking = bookings.lockOwned(bookingId, userId)
			.orElseThrow(() -> new BookingNotFoundException(bookingId));
		Optional<Payment> open = payments.findByBookingIdAndStatus(bookingId, PaymentStatus.PENDING);
		if (open.isPresent()) {
			// A racing request recorded it first; with the same Stripe key it's the same session
			if (!open.get().getProviderSessionId().equals(session.id())) {
				closeQuietly(session);
			}
			return new StartedPayment(mapper.toResponse(open.get()), false);
		}
		Instant now = clock.instant();
		booking.startPayment(now);
		Payment payment = payments.save(new Payment(bookingId, session.id(), session.url(), checkout.amount(),
				properties.currency(), now));
		return new StartedPayment(mapper.toResponse(payment), true);
	}

	/** "Deluxe at Seaside Inn, 2 nights, 1 room": what the guest sees on Stripe's page. */
	private static String describe(Booking booking) {
		Room room = booking.getRoom();
		long nights = ChronoUnit.DAYS.between(booking.getCheckIn(), booking.getCheckOut());
		int rooms = booking.getRoomsCount();
		return room.getType() + " at " + room.getHotel().getName() + ", " + nights + (nights == 1 ? " night" : " nights")
				+ ", " + rooms + (rooms == 1 ? " room" : " rooms");
	}

	private void closeQuietly(CheckoutSession session) {
		try {
			gateway.expireCheckoutSession(session.id());
		}
		catch (ApiException ex) {
			// Unpaid sessions expire on their own; a late payment is confirmed or refunded
			log.warn("Couldn't close checkout session {}", session.id(), ex);
		}
	}

}
