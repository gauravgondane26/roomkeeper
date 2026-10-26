package com.roomkeeper.booking;

import java.time.Instant;

/** Where a booking is, for clients polling it while the rooms are held. */
public record BookingStatusResponse(Long id, BookingStatus status, Instant holdExpiresAt) {
}
