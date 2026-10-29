package com.roomkeeper.outbox;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A payment confirmed the booking: what the guest's confirmation says. It carries the
 * details as they were at that moment, so sending it doesn't depend on the booking later.
 */
public record BookingConfirmed(long bookingId, long userId, String hotel, String roomType, LocalDate checkIn,
		LocalDate checkOut, int rooms, BigDecimal amount, String currency) implements OutboxPayload {

	@Override
	public OutboxEventType type() {
		return OutboxEventType.BOOKING_CONFIRMED;
	}

	@Override
	public long aggregateId() {
		return bookingId;
	}

}
