package com.roomkeeper.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Rooms freed up and were held for a guest who was waiting for them: booking
 * {@code bookingId}, theirs until {@code holdExpiresAt}, when it goes to the next in line.
 */
public record WaitlistOffered(long bookingId, long userId, String hotel, String roomType, LocalDate checkIn,
		LocalDate checkOut, int rooms, BigDecimal amount, Instant holdExpiresAt) implements OutboxPayload {

	@Override
	public OutboxEventType type() {
		return OutboxEventType.WAITLIST_OFFERED;
	}

	@Override
	public long aggregateId() {
		return bookingId;
	}

}
