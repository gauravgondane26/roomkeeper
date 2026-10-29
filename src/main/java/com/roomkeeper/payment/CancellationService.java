package com.roomkeeper.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.roomkeeper.booking.Booking;
import com.roomkeeper.booking.BookingNotFoundException;
import com.roomkeeper.booking.BookingRepository;
import com.roomkeeper.booking.BookingStatus;
import com.roomkeeper.booking.CancellationPolicy;
import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryRepository;
import com.roomkeeper.outbox.BookingCancelled;
import com.roomkeeper.outbox.OutboxWriter;
import com.roomkeeper.outbox.RefundDue;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guests cancel confirmed bookings. In one transaction the rooms go back on sale, the
 * booking becomes CANCELLED, and the refund the cancellation policy allows is made due:
 * recorded on the payment and written to the outbox, with the guest's cancellation email.
 * The outbox then makes the refund, exactly once, however Stripe or the app fails meanwhile.
 */
@Service
public class CancellationService {

	private static final Set<PaymentStatus> REFUNDS = EnumSet.of(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);

	private final BookingRepository bookings;

	private final PaymentRepository payments;

	private final InventoryRepository inventory;

	private final OutboxWriter outbox;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public CancellationService(BookingRepository bookings, PaymentRepository payments, InventoryRepository inventory,
			OutboxWriter outbox, ApplicationEventPublisher events, Clock clock) {
		this.bookings = bookings;
		this.payments = payments;
		this.inventory = inventory;
		this.outbox = outbox;
		this.events = events;
		this.clock = clock;
	}

	/** Cancels the user's confirmed booking, or returns the cancellation it already has. */
	@Transactional
	public CancellationResponse cancel(long userId, long bookingId) {
		// The booking first, then its nights in date order, as every writer locks them
		Booking booking = bookings.lockOwned(bookingId, userId)
			.orElseThrow(() -> new BookingNotFoundException(bookingId));
		if (booking.getStatus() == BookingStatus.CANCELLED) {
			// A retried request: the cancellation stands, with the refund it made due
			return response(booking,
					payments.findFirstByBookingIdAndStatusInOrderByIdDesc(bookingId, REFUNDS).orElseThrow());
		}
		if (booking.getStatus() != BookingStatus.CONFIRMED) {
			throw new ApiException(ProblemType.BOOKING_STATE,
					"Booking " + bookingId + " is " + booking.getStatus() + "; only a paid booking can be cancelled");
		}
		// A confirmed booking has exactly one paid payment
		Payment payment = payments.findByBookingIdAndStatus(bookingId, PaymentStatus.SUCCEEDED).orElseThrow();
		Room room = booking.getRoom();
		Hotel hotel = room.getHotel();
		BigDecimal refund = CancellationPolicy.refund(payment.getAmount(), booking.getCheckIn(),
				ZoneId.of(hotel.getTimeZone()), clock.instant());

		LocalDate lastNight = booking.lastNight();
		List<Inventory> nights = inventory.lockNights(room.getId(), booking.getCheckIn(), lastNight);
		// A night with no room left before the cancellation has one again after it
		boolean reopened = Inventory.anySoldOut(nights);
		nights.forEach(night -> night.unbook(booking.getRoomsCount()));
		booking.cancel();
		payment.refundDue(refund);

		events.publishEvent(
				InventoryChangedEvent.sale(hotel.getId(), hotel.getCity(), booking.getCheckIn(), lastNight, reopened));
		outbox.write(new RefundDue(booking.getId(), payment.getId(), payment.getPaymentIntentId(), refund));
		outbox.write(new BookingCancelled(booking.getId(), booking.getUserId(), hotel.getName(), room.getType(),
				booking.getCheckIn(), booking.getCheckOut(), booking.getRoomsCount(), refund, payment.getCurrency()));
		return response(booking, payment);
	}

	private static CancellationResponse response(Booking booking, Payment payment) {
		CancellationResponse.RefundStatus refundStatus = payment.getStatus() == PaymentStatus.REFUNDED
				? CancellationResponse.RefundStatus.REFUNDED : CancellationResponse.RefundStatus.PENDING;
		return new CancellationResponse(booking.getId(), booking.getStatus(), payment.getRefundedAmount(),
				payment.getCurrency(), refundStatus);
	}

}
