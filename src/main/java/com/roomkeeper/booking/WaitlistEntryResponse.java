package com.roomkeeper.booking;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A place in line. {@code bookingId} is the hold made for the guest once rooms were offered
 * ({@code OFFERED}); from there it's a normal booking, to add guests to and pay for in time.
 */
public record WaitlistEntryResponse(long id, long roomId, LocalDate checkIn, LocalDate checkOut, int rooms,
		WaitlistStatus status, Long bookingId, Instant createdAt, Instant offeredAt) {

	static WaitlistEntryResponse of(WaitlistEntry entry) {
		return new WaitlistEntryResponse(entry.getId(), entry.getRoom().getId(), entry.getCheckIn(), entry.getCheckOut(),
				entry.getRoomsCount(), entry.getStatus(), entry.getBookingId(), entry.getCreatedAt(),
				entry.getOfferedAt());
	}

}
