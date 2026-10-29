package com.roomkeeper.payment;

import java.math.BigDecimal;

import com.roomkeeper.booking.BookingStatus;

/** A cancelled booking, and the refund it's due: on its way (PENDING), or made (REFUNDED). */
public record CancellationResponse(long bookingId, BookingStatus status, BigDecimal refund, String currency,
		RefundStatus refundStatus) {

	public enum RefundStatus {
		PENDING, REFUNDED
	}

}
