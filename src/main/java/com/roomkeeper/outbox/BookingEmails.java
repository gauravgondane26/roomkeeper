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
	public Set<OutboxEventType> types() {
		return EnumSet.of(OutboxEventType.BOOKING_CONFIRMED);
	}

	@Override
	public void consume(OutboxEvent event) {
		BookingConfirmed booking = json.readValue(event.getPayload(), BookingConfirmed.class);
		// Their address now, not when they booked
		String to = users.findById(booking.userId())
			.map(User::getEmail)
			.orElseThrow(() -> new IllegalStateException("User " + booking.userId() + " not found"));
		sender.send(new Email("outbox-" + event.getId(), to, "Your stay at " + booking.hotel() + " is confirmed",
				"Booking " + booking.bookingId() + ": " + booking.rooms() + " x " + booking.roomType() + ", "
						+ booking.checkIn() + " to " + booking.checkOut() + ". Paid " + booking.amount() + " "
						+ booking.currency().toUpperCase() + "."));
	}

}
