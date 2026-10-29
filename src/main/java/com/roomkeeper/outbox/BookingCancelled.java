package com.roomkeeper.outbox;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The guest cancelled the booking: what their cancellation email says, and what comes back. */
public record BookingCancelled(long bookingId, long userId, String hotel, String roomType, LocalDate checkIn,
		LocalDate checkOut, int rooms, BigDecimal refund, String currency) implements OutboxPayload {

	@Override
	public OutboxEventType type() {
		return OutboxEventType.BOOKING_CANCELLED;
	}

	@Override
	public long aggregateId() {
		return bookingId;
	}

}
