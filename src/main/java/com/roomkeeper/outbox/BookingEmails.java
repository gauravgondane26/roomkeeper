package com.roomkeeper.outbox;

import java.util.EnumSet;
import java.util.Set;

import com.roomkeeper.user.User;
import com.roomkeeper.user.UserRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emails guests about their bookings. Each email's idempotency key is its outbox event's
 * id, so an event handed over twice sends one email.
 */
@Component
class BookingEmails implements OutboxConsumer {

	private final EmailSender sender;

	private final UserRepository users;

	private final JsonMapper json;

	BookingEmails(EmailSender sender, UserRepository users, JsonMapper json) {
		this.sender = sender;
		this.users = users;
		this.json = json;
	}

	@Override
	public String name() {
		return "booking-emails";
	}

	@Override
	public Set<OutboxEventType> types() {
		return EnumSet.of(OutboxEventType.BOOKING_CONFIRMED, OutboxEventType.BOOKING_CANCELLED,
				OutboxEventType.WAITLIST_OFFERED);
	}

	@Override
	public void consume(OutboxEvent event) {
		String key = "outbox-" + event.getId();
		switch (event.getType()) {
			case BOOKING_CONFIRMED -> {
				BookingConfirmed booking = json.readValue(event.getPayload(), BookingConfirmed.class);
				sender.send(new Email(key, addressOf(booking.userId()),
						"Your stay at " + booking.hotel() + " is confirmed",
						"Booking " + booking.bookingId() + ": " + booking.rooms() + " x " + booking.roomType() + ", "
								+ booking.checkIn() + " to " + booking.checkOut() + ". Paid " + booking.amount() + " "
								+ booking.currency().toUpperCase() + "."));
			}
			case BOOKING_CANCELLED -> {
				BookingCancelled booking = json.readValue(event.getPayload(), BookingCancelled.class);
				sender.send(new Email(key, addressOf(booking.userId()),
						"Your booking " + booking.bookingId() + " is cancelled",
						booking.rooms() + " x " + booking.roomType() + " at " + booking.hotel() + ", "
								+ booking.checkIn() + " to " + booking.checkOut() + ". A refund of " + booking.refund()
								+ " " + booking.currency().toUpperCase() + " is on its way."));
			}
			case WAITLIST_OFFERED -> {
				WaitlistOffered offer = json.readValue(event.getPayload(), WaitlistOffered.class);
				sender.send(new Email(key, addressOf(offer.userId()), "Rooms are held for you at " + offer.hotel(),
						offer.rooms() + " x " + offer.roomType() + ", " + offer.checkIn() + " to " + offer.checkOut()
								+ ", came free while you were waiting. Booking " + offer.bookingId() + " holds them for you"
								+ " until " + offer.holdExpiresAt() + " (UTC): add your guests and pay " + offer.amount()
								+ " before then, or they go to the next guest waiting."));
			}
			default -> throw new IllegalArgumentException("Booking emails don't cover " + event.getType());
		}
	}

	/** The user's address now, not when they booked. */
	private String addressOf(long userId) {
		return users.findById(userId)
			.map(User::getEmail)
			.orElseThrow(() -> new IllegalStateException("User " + userId + " not found"));
	}

}
