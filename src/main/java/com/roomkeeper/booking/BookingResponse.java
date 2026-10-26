package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A booking as its guest sees it. {@code amount} is the total for every room and night,
 * fixed when the rooms were held.
 */
public record BookingResponse(Long id, Long hotelId, String hotelName, Long roomId, String roomType, LocalDate checkIn,
		LocalDate checkOut, int rooms, BookingStatus status, BigDecimal amount, Instant holdExpiresAt) {
}
