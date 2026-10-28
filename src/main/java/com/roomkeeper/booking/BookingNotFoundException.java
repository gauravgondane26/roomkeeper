package com.roomkeeper.booking;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

/** The booking doesn't exist, or belongs to someone else: guests can't tell the two apart. */
public class BookingNotFoundException extends ApiException {

	public BookingNotFoundException(long bookingId) {
		super(ProblemType.NOT_FOUND, "Booking " + bookingId + " not found");
	}

}
